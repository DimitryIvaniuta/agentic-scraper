package com.components.scraper.ai;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.components.scraper.config.OpenAIProperties;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

@Slf4j
@Component
public class LLMHelper {

    /** Endpoint for completions */
    private static final String CHAT_COMPLETION_ENDPOINT = "/chat/completions";

    /** Maximum LLM retries before giving up */
    private static final int MAX_RETRIES = 1;

    /** Timeout for the HTTP call */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** All valid cate values (expand whenever Murata introduces a new category) */
    private static final Set<String> VALID_CATE_SET = Set.of(
            "luCeramicCapacitorsSMD",
            "luCeramicCapacitorsLead",
            "luThermistorsNTC",
            "luThermistorsPTC",
            "luTrimmerPotentiometers",
            "luInductorWirewound",
            "luInductorMultilayer",
            "luEMIFiltersChipBeads",
            "luEMIFiltersArrays",
            "luResonatorsCeralock",
            "luSensorsPyroelectric",
            "luSAWDevices",
            "luPowerModules",
            "cgsubTrimmPoten"
    );

    private static final String SYSTEM_PROMPT_TEMPLATE = """
    You are an expert on Murata’s PsdispRest API.
    Given only a valid Murata part number, return exactly the correct `cate` query value
    used in the endpoint:
      https://www.murata.com/webapi/PsdispRest?cate=…
    Do not include anything else—no quotes, no explanation, no formatting, no whitespace.
    
    Use these exact examples to guide you:
    %s
    
    When asked, respond with **only** the cate value.
    """;

    /** Example part-to-cate mappings for the system prompt */
    private static final List<String> SAMPLE_CASES = List.of(
            "GRM0115C1CR20 → luCeramicCapacitorsSMD",
            "LQH32CN220K23 → luInductorWirewound",
            "PV12P200A01B00 → cgsubTrimmPoten"
    );

    /**
     * Configuration properties (API key, base URL, default model).
     */
    private final OpenAIProperties props;

    /**
     * Resilience4j retry instance, configured for AI calls.
     */
    private final Retry retry;

    /**
     * Resilience4j circuit breaker instance, configured for AI calls.
     */
    private final CircuitBreaker circuitBreaker;

    /** quick local cache to avoid repeated lookups */
    private final Map<String, String> cateCache = new ConcurrentHashMap<>();

    /** LLM client */
    private final WebClient openAiClientWeb;

    public LLMHelper(OpenAIProperties props, Retry retry, CircuitBreaker circuitBreaker) {
        this.props = Objects.requireNonNull(props);
        this.retry = Objects.requireNonNull(retry);
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker);
        this.openAiClientWeb = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getKey())
                .build();
    }

    public String determineCate(final String partNumber,
                                final UnaryOperator<String> fallback) {

        Supplier<String> aiLookup = () -> callAiForCate(partNumber);

        Supplier<String> decorated = Decorators
                .ofSupplier(aiLookup)
                .withRetry(retry)
                .withCircuitBreaker(circuitBreaker)
                .withFallback(
                        List.of(Exception.class),
                        ex -> {
                            log.warn("AI category lookup failed for part “{}”: {} → using fallback",
                                    partNumber, ex.toString());
                            return fallback.apply(partNumber);
                        })
                .decorate();

        return decorated.get();
    }

    public String callAiForCate(String partNumber) {
        if (partNumber == null || partNumber.isBlank()) {
            throw new IllegalArgumentException("partNumber is blank");
        }
        // normalize part number to uppercase (Murata parts are case‑insensitive)
        partNumber = partNumber.trim().toUpperCase();

        // 1) cache short‑circuit
        String cached = cateCache.get(partNumber);
        if (cached != null) {
            return cached;
        }

        // 2) attempt LLM with optional retry
        String cate = null;
//        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                cate = askModelForCate(partNumber, false /*use stricter prompt on retry*/);
                if (isValidCate(cate)) {
                    cateCache.put(partNumber, cate);
                    return cate;
                }
//                log.warn("Received invalid cate '{}' for part {} (attempt {})", cate, partNumber, attempt + 1);
                log.warn("Received invalid cate '{}' for part {}", cate, partNumber);
            } catch (Exception e) {
//                log.error("LLM invocation failed on attempt {} for part {}: {}", attempt + 1, partNumber, e.getMessage());
                log.error("LLM invocation failed for part {}: {}", partNumber, e.getMessage());
            }
//        }
        return cate;
//        throw new IllegalStateException("Unable to resolve cate for part " + partNumber + " after " + MAX_RETRIES + " attempts. Last result: " + cate);
    }
    private boolean isValidCate(String cate) {
        return cate != null && VALID_CATE_SET.contains(cate);
    }

    private String askModelForCate(String partNumber, boolean strictMode) {
        Map<String, Object> system = Map.of(
                "role", "system",
                "content", buildSystemPrompt(strictMode)
        );
        Map<String, Object> user = Map.of("role", "user", "content", partNumber);
        Map<String, Object> payload = Map.of(
                "model", props.getDefaultModel(),
                "temperature", 0,
                "messages", List.of(system, user)
        );
log.info("Start to retrieve cate: {}", partNumber);
        JsonNode response = openAiClientWeb.post()
                .uri(URI.create(props.getBaseUrl() + CHAT_COMPLETION_ENDPOINT))
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(TIMEOUT)
                .block();

        if (response == null) {
            throw new IllegalStateException("Null response from LLM");
        }

        String result = response.at("/choices/0/message/content").asText().trim();
        // remove any accidental quotes or formatting characters
        result = result.replaceAll("[\"'`\\s]", "");

        log.info("Start to retrieve cate: code: {} with result {}", partNumber, result);
        return result;
    }

//    private String buildSystemPrompt(boolean strictMode) {
//        return "You are an expert on Murata’s PsdispRest API. Given only a valid Murata part number, " +
//                "return exactly the correct cate query value used in the endpoint https://www.murata.com/webapi/PsdispRest?cate=…. " +
//                "Do not include anything else—no quotes, no explanation, no formatting, no whitespace. " +
//                "Use these exact examples to guide you:\n\nGRM0115C1CR20 → luCeramicCapacitorsSMD" +
//                " \nLQH32CN220K23 → luInductorWirewound\nPV12P200A01B00 → cgsubTrimmPoten" +
//                " \n\nWhen asked, respond with only the cate value.";
//
//    }


    /**
     * Builds the system prompt for OpenAI, optionally adding a brevity hint
     * on retries.
     *
     * @param strictMode if true, append a note to keep responses extra‐concise
     */
    private String buildSystemPrompt(boolean strictMode) {
        String examples = String.join("\n", SAMPLE_CASES);
        String prompt = String.format(SYSTEM_PROMPT_TEMPLATE, examples);
        if (strictMode) {
            prompt += "\n\n(Note: On retry, please be even more concise.)";
        }
        return prompt;
    }

}
