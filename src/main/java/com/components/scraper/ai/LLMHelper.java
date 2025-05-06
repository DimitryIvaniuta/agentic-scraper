package com.components.scraper.ai;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
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

    /**
     * Endpoint for completions.
     */
    private static final String CHAT_COMPLETION_ENDPOINT = "/chat/completions";

    /**
     * Timeout for the HTTP call.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /**
     * All valid cate values (expand whenever Murata introduces a new category).
     */
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

    /**
     * OpenAI content message text.
     */
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

    /**
     * Example part-to-cate mappings for the system prompt.
     */
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

    /**
     * quick local cache to avoid repeated lookups.
     */
    private final Map<String, String> cateCache = new ConcurrentHashMap<>();

    /**
     * LLM client.
     */
    private final WebClient openAiClientWeb;

    /**
     * Creates a new {@code LLMHelper}, initializing the OpenAI client and resilience components.
     *
     * <p>This constructor wires in:
     * <ul>
     *   <li>{@link OpenAIProperties} containing API key, base URL, and default model.</li>
     *   <li>A Resilience4j {@link Retry} instance to automatically retry transient failures.</li>
     *   <li>A Resilience4j {@link CircuitBreaker} to prevent cascading failures when the OpenAI endpoint is unavailable.</li>
     * </ul>
     * It also builds a dedicated {@link WebClient} instance pre-configured with the
     * OpenAI base URL and Bearer authorization header for subsequent AI calls.</p>
     *
     * @param props            configuration properties for OpenAI (must not be {@code null})
     * @param retry            Resilience4j retry configuration (must not be {@code null})
     * @param circuitBreaker   Resilience4j circuit breaker configuration (must not be {@code null})
     * @throws NullPointerException if any of the parameters are {@code null}
     */
    public LLMHelper(final OpenAIProperties props, final Retry retry, final CircuitBreaker circuitBreaker) {
        this.props = Objects.requireNonNull(props);
        this.retry = Objects.requireNonNull(retry);
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker);
        this.openAiClientWeb = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getKey())
                .build();
    }

    /**
     * Attempts to determine the exact Murata <code>cate</code> value for a given part number by
     * delegating to the AI (OpenAI chat completions) and falling back if necessary.
     * <p>
     * This method will:
     * <ol>
     *   <li>Invoke the AI lookup wrapped in Resilience4j {@code Retry} and {@code CircuitBreaker}.</li>
     *   <li>On any failure (exception, open circuit, too many attempts), log a warning and invoke
     *       the provided <code>fallback</code> function to compute a safe default.</li>
     * </ol>
     *
     * @param partNumber the Murata part number (must not be {@code null} or blank)
     * @param fallback   a function which, given the same part number, returns a fallback
     *                   <code>cate</code> value (for example, based on prefix lookup)
     * @return the AI‐determined <code>cate</code> string, or the result of
     *         <code>fallback.apply(partNumber)</code> if AI fails
     * @throws IllegalArgumentException if <code>partNumber</code> is {@code null} or blank
     */
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

    /**
     * Calls the AI model once (without external retry decoration) to retrieve a raw
     * <code>cate</code> value for the given part number, applies basic validation and
     * caches the result for future calls.
     *
     * <p>This method:
     * <ul>
     *   <li>Validates that <code>partNumber</code> is non‐blank.</li>
     *   <li>Normalizes to uppercase and trims whitespace.</li>
     *   <li>Checks an in‐memory cache for a previously computed value.</li>
     *   <li>Invokes {@link #askModelForCate(String, boolean)} to fetch from the LLM once.</li>
     *   <li>Validates against a known set of allowed <code>cate</code> values.</li>
     *   <li>If valid, stores in cache and returns; otherwise logs a warning and returns
     *       the (invalid) string for further handling by the caller.</li>
     * </ul>
     *
     * @param partNumber the normalized Murata part number (non‐blank)
     * @return the raw <code>cate</code> string returned by the model (may be invalid)
     * @throws IllegalArgumentException if <code>partNumber</code> is {@code null} or blank
     * @throws IllegalStateException    if the AI returned <code>null</code> or an unexpected error occurred
     */
    public String callAiForCate(final String partNumber) {
        if (partNumber == null || partNumber.isBlank()) {
            throw new IllegalArgumentException("partNumber is blank");
        }
        // normalize part number to uppercase (Murata parts are case‑insensitive)
        String partNumberNormalized = partNumber.trim().toUpperCase();

        // 1) cache short‑circuit
        String cached = cateCache.get(partNumberNormalized);
        if (cached != null) {
            return cached;
        }

        // 2) attempt LLM with optional retry
        String cate = null;
        try {
            cate = askModelForCate(partNumberNormalized, false /*use stricter prompt on retry*/);
            if (isValidCate(cate)) {
                cateCache.put(partNumberNormalized, cate);
                return cate;
            }
            log.warn("Received invalid cate '{}' for part {}", cate, partNumberNormalized);
        } catch (Exception e) {
            log.error("LLM invocation failed for part {}: {}", partNumberNormalized, e.getMessage());
        }
        return cate;
    }

    /**
     * Validates that a <code>cate</code> string is one of the known, supported Murata categories.
     *
     * @param cate the AI‐returned cate string (may be {@code null})
     * @return {@code true} if <code>cate</code> is non‐null and is contained in the
     *         {@link #VALID_CATE_SET}; {@code false} otherwise
     */
    private boolean isValidCate(final String cate) {
        return cate != null && VALID_CATE_SET.contains(cate);
    }

    /**
     * Constructs and sends the chat completion request to OpenAI, retrieves the response JSON,
     * and extracts the first choice’s <code>message.content</code> value.
     * <p>
     * The prompts are built as:
     * <ul>
     *   <li><strong>system</strong> prompt via {@link #buildSystemPrompt(boolean)}</li>
     *   <li><strong>user</strong> message containing only the part number</li>
     * </ul>
     * The call is performed with a strict timeout and any network or server error will
     * be propagated to the caller.
     *
     * @param partNumber the normalized Murata part number to ask the LLM about
     * @param strictMode if {@code true}, enables any stricter prompt instructions (currently unused)
     * @return the raw <code>message.content</code> string from the first choice (trimmed and sanitized)
     * @throws IllegalStateException on null or malformed response
     */
    private String askModelForCate(final String partNumber, final boolean strictMode) {
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

    /**
     * Builds the system prompt for OpenAI, optionally adding a brevity hint
     * on retries.
     *
     * @param strictMode if true, append a note to keep responses extra‐concise
     * @return system reply prompt text
     */
    private String buildSystemPrompt(final boolean strictMode) {
        String examples = String.join("\n", SAMPLE_CASES);
        String prompt = String.format(SYSTEM_PROMPT_TEMPLATE, examples);
        if (strictMode) {
            prompt += "\n\n(Note: On retry, please be even more concise.)";
        }
        return prompt;
    }
}
