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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * <h2>LLMHelper</h2>
 *
 * <p>
 * Wraps calls to OpenAI’s Chat Completions endpoint in a
 * Resilience4j <strong>Retry</strong> (with exponential backoff) and
 * <strong>CircuitBreaker</strong>.  In case of repeated failures
 * (HTTP 429, 5xx, network errors, or open circuit), falls back
 * to a provided prefix‐lookup function.
 * </p>
 *
 * <p>
 * Usage:
 * <pre>
 *   String cate = llmHelper.determineCate(
 *       partNo,
 *       pn -> vendorPrefixLookup(pn)
 *   );
 * </pre>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMHelper {

    /**
     * Spring’s declarative HTTP client for calling OpenAI.
     */
    private final RestClient openAiClient;

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
     * Attempts to determine the exact Murata {@code cate} value for the given
     * <code>partNumber</code> via OpenAI’s chat completion API.  If the AI call
     * ultimately fails (after retries or an open circuit), invokes the provided
     * <code>fallback</code> function to compute a safe default.
     *
     * @param partNumber the Murata part number, e.g. “GRM0115C1C100GE01”
     * @param fallback   a Function that maps the same part number to a fallback
     *                   cate value (e.g. prefix-based lookup)
     * @return the AI‐determined cate value, or the fallback result if AI fails
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
     * Performs the actual POST to OpenAI’s
     * <code>/chat/completions</code> endpoint and extracts the
     * assistant’s message content.
     *
     * @param partNumber the Murata part number to embed in the prompt
     * @return the raw content returned by the model (e.g. “luCeramicCapacitorsSMD”)
     * @throws RuntimeException on JSON‐parsing or HTTP errors
     */
    private String callAiForCate(final String partNumber) {
        // build the Chat payload
        Map<String, Object> payload = Map.of(
                "model", props.getDefaultModel(),
                "messages", List.of(
                        Map.of(
                                "role",    "system",
                                "content", "You are an expert on Murata's API.  Given the part number \"%s\", "
                                        + " return ONLY the exact value of the cate query parameter "
                                        + " you would use on https://www.murata.com/webapi/PsdispRest?cate=.... "
                                        + "Example: luCeramicCapacitorsSMD Do not include any extra text or punctuation. "
                        ),
                        Map.of(
                                "role",    "user",
                                "content", "Part number: \"" + partNumber + "\""
                        )
                )
        );
        // invoke OpenAI
        JsonNode response = openAiClient
                .post()
                .uri(URI.create(props.getBaseUrl() + "/chat/completions"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getKey())
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        // extract the first choice’s message.content
        return response == null ? "" : response
                .at("/choices/0/message/content")
                .asText()
                .trim();
    }
}
