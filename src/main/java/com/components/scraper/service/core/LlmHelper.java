package com.components.scraper.service.core;


import com.components.scraper.config.OpenAIProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Helper for decision-making tasks using a Large Language Model (LLM), with
 * a heuristic fallback when no API key is provided or the request fails.
 */
@Component
public class LlmHelper {

    /**
     * Very small excerpt.  Extend it as you need – Murata publish the full
     * series list in every data‑book and on their “Part‑Number Structure” page.
     */
/*
    private static final Map<String, String> SERIES_TO_CATE = Map.ofEntries(
            Map.entry("GRM", "luCeramicCapacitorsSMD"),
            Map.entry("GCM", "luCeramicCapacitorsSMD"),
            Map.entry("LQH", "luInductorSMD"),
            Map.entry("BLM", "luEMIFiltersSMD"),
            Map.entry("PKL", "luPiezoSoundComponents"),
            Map.entry("NFM", "luEMIPiFiltersSMD")
    );
*/

    /**
     * OpenAI API key used to authenticate LLM requests.
     */
    private final OpenAIProperties apiKeyProperties;

    /**
     * HTTP client instance for sending requests to the LLM endpoint.
     */
    private final HttpClient httpClient;

    /**
     * JSON mapper for serializing prompts and deserializing responses.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Constructs an LLMHelper, sourcing the API key from the
     * {@code OPENAI_API_KEY} environment variable if not explicitly provided.
     *
     * @param apiKeyProperties optional OpenAI API key properties
     * if null or blank, will fall back to env var.
     */
    public LlmHelper(@Autowired OpenAIProperties apiKeyProperties) {
        String key = Optional.ofNullable(apiKeyProperties.getApiKey())
                .filter(s -> !s.isBlank())
                .orElse(System.getenv("OPENAI_API_KEY"));
        if (key == null || key.isBlank()) {
            System.err.println("LLMHelper: no API key provided; falling back to heuristics only.");
        }
        apiKeyProperties.setApiKey(key);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.apiKeyProperties = apiKeyProperties;
    }

    /**
     * Determine the most likely category navigation path for a given MPN.
     * Queries the LLM if an API key is available; otherwise uses a heuristic fallback.
     *
     * @param mpn                 the manufacturer part number to classify
     * @param availableCategories list of top-level categories to choose from
     * @return a list of category names (top-level first, then subcategories)
     */
    public List<String> determineCategoryPath(String mpn, List<String> availableCategories) {
        if (apiKeyProperties.getApiKey() == null) {
            return fallbackCategoryPath(mpn);
        }

        // Build the prompt
        String prompt = """
            I need to determine the most likely product category path for an electronic component with part number "%s".
            
            Available top-level categories:
            %s
            
            Return your answer as a JSON array of category names, starting with the top-level category.
            Example: ["Capacitors","Ceramic Capacitors"]
            """.formatted(
                mpn,
                objectMapper.valueToTree(availableCategories).toString()
        );

        try {
            String response = callOpenAi(prompt);
            JsonNode root = objectMapper.readTree(response);
            if (root.isArray()) {
                return objectMapper.convertValue(root, objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, String.class));
            }
        } catch (Exception e) {
            System.err.println("LLMHelper.determineCategoryPath error: " + e.getMessage());
        }

        return fallbackCategoryPath(mpn);
    }

    /**
     * Identify which section index (0-based) among a list of text sections most
     * likely contains a given parameter name, using the LLM when possible.
     *
     * @param paramName   the name of the parameter to locate
     * @param sectionTexts the text of each section to search within
     * @return an Optional containing the 0-based index of the matching section, or empty if none
     */
    public Optional<Integer> identifyParameterSection(String paramName, List<String> sectionTexts) {
        if (apiKeyProperties.getApiKey() == null || sectionTexts.isEmpty()) {
            return Optional.empty();
        }

        // Build the prompt
        StringBuilder sb = new StringBuilder();
        sb.append("I have the following sections and need to find which one contains the parameter \"")
                .append(paramName)
                .append("\":\n");
        for (int i = 0; i < sectionTexts.size(); i++) {
            sb.append(i + 1).append(". ").append(sectionTexts.get(i)).append("\n");
        }
        sb.append("Respond with only the 1-based section number, or \"None\".");

        try {
            String response = callOpenAi(sb.toString()).trim();
            if (response.matches("\\d+")) {
                int idx = Integer.parseInt(response) - 1;
                if (idx >= 0 && idx < sectionTexts.size()) {
                    return Optional.of(idx);
                }
            }
        } catch (Exception e) {
            System.err.println("LLMHelper.identifyParameterSection error: " + e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Low-level method to call the OpenAI Chat Completion endpoint.
     *
     * @param userPrompt the prompt to send
     * @return the raw string content of the LLM's reply
     * @throws IOException          on network or parsing errors
     * @throws InterruptedException if the request is interrupted
     */
    private String callOpenAi(String userPrompt) throws IOException, InterruptedException {
        // Build the JSON payload
        String payload = objectMapper.writeValueAsString(
                objectMapper.createObjectNode()
                        .put("model", "gpt-3.5-turbo")
                        .set("messages", objectMapper.createArrayNode()
                                .add(objectMapper.createObjectNode()
                                        .put("role", "user")
                                        .put("content", userPrompt)
                                )
                        )
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKeyProperties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("OpenAI API returned status " + resp.statusCode());
        }

        JsonNode root = objectMapper.readTree(resp.body());
        return root
                .path("choices")
                .get(0)
                .path("message")
                .path("content")
                .asText();
    }

    /**
     * Fallback heuristic to determine category based on common MPN patterns.
     *
     * @param mpn the part number to analyze
     * @return a default category path
     */
    private List<String> fallbackCategoryPath(String mpn) {
        String up = mpn.toUpperCase();
        if (up.contains("CAP") || up.contains("GRM") || up.contains("C0G") || up.contains("X7R")) {
            return List.of("Capacitors", "Ceramic Capacitors");
        }
        if (up.contains("RES") || up.contains("RC") || up.contains("RL")) {
            return List.of("Resistors", "Chip Resistors");
        }
        if (up.contains("IND") || up.contains("LQG") || up.contains("LQW")) {
            return List.of("Inductors", "Chip Inductors");
        }
        return Collections.singletonList("Capacitors");
    }

    public List<String> guessCategoryPath(String competitorMpn) {
        if (competitorMpn.startsWith("GR"))
            return List.of("Capacitors", "Ceramic Capacitors");
        return Collections.emptyList();
    }

    /** Returns the cate= code or {@code Optional.empty()} if the prefix is unknown. */
/*
    public Optional<String> cateFromPrefix(String partNo) {
        if (partNo == null || partNo.length() < 3) return Optional.empty();
        String prefix = partNo.substring(0, 3).toUpperCase(Locale.ROOT);
        return Optional.ofNullable(SERIES_TO_CATE.get(prefix));
    }
*/

}