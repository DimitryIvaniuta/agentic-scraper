package com.components.scraper.service.tdk;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.ParametricFilterConfig;
import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.ParametricSearchService;
import com.components.scraper.service.core.VendorSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;


import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h2>TDK – Parametric Search Service</h2>
 * <p>Implements parametric search against TDK's PDC API:
 * <code>/pdc_api/en/search/list/search_result</code> with free-text support via LLM.</p>
 * <p>Supports:</p>
 * <ul>
 *   <li>Exact parameter filters as scon query params</li>
 *   <li>Free-text "details" routing through {@link LLMHelper}</li>
 *   <li>Reuse of connection pool, anti-bot cookies, Brotli, and warm-up</li>
 * </ul>
 */
@Slf4j
@Service("tdkParamSvc")
@ConditionalOnProperty(prefix = "scraper.configs.tdk", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TdkParametricSearchService extends TdkSearchEngine implements ParametricSearchService {

    /** Key for free-text "details" parameter. */
    private static final String DETAILS_KEY = "details";

    private static final Pattern DETAILS_FILTER_PATTERN = Pattern.compile("([^\"]+)");

    /**
     * Constructs the TDK parametric search service.
     * @param filterConfig YAML-backed filter definitions
     * @param factory vendor config factory
     * @param builder shared WebClient.Builder
     * @param llmHelper LLM helper for free-text filters
     * @param parser JSON grid parser for structured results
     * @param mapper shared JSON mapper
     */
    public TdkParametricSearchService(
            final ParametricFilterConfig filterConfig,
            final VendorConfigFactory factory,
            final WebClient.Builder builder,
            final LLMHelper llmHelper,
            @Qualifier("tdkGridParser") final JsonGridParser parser,
            @Qualifier("scraperObjectMapper") final ObjectMapper mapper) {
        super(factory.forVendor("tdk"), builder, llmHelper, parser, mapper);
    }

    /**
     * Performs a parametric search using exact parameters and optional free text.
     * @param category main category label
     * @param subcategory optional subcategory
     * @param parameters map of parameter key→value or range or collection
     * @param maxResults maximum rows to return
     * @return list of row maps matching filters
     */
    @Override
    public List<Map<String,Object>> searchByParameters(
            final String category,
            final String subcategory,
            final Map<String,Object> parameters,
            final int maxResults) {

        // Build form data
        MultiValueMap<String,String> form = buildForm(category, subcategory, parameters, maxResults);
        URI endpoint = buildUri(getCfg().getBaseUrl(), getCfg().getParametricSearchUrl(), null);

        JsonNode rsp = safePost(endpoint, form);
        return getParser().parse(rsp).stream()
                .limit(maxResults)
                .toList();
    }

    /**
     * Builds the URL-encoded form data for the parametric search,
     * including free-text "details" via LLM.
     */
    private MultiValueMap<String,String> buildForm(
            final String category,
            final String subcategory,
            final Map<String,Object> params,
            final int maxResults) {

        MultiValueMap<String,String> form = new LinkedMultiValueMap<>();
        form.add("site",   getSite().get());
        form.add("charset","UTF-8");
        form.add("group",  getGroup().get());
        form.add("design", getDesign().get());
        form.add("fromsyncsearch","1");
        form.add("_l", String.valueOf(maxResults));
        form.add("_p","1");
        form.add("_c","part_no-part_no");
        form.add("_d","0");

        // category context as a hidden param
        form.add("category", Optional.ofNullable(category).orElse(""));
        if (subcategory != null) form.add("sub_category", subcategory);

        // apply structured and free-text filters
        for (Map.Entry<String,Object> e : params.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();

            if (DETAILS_KEY.equals(key) && value instanceof String text) {
                // free-text to structured via LLM
                JsonNode filters = llmHelper.classify(text);
                Optional.ofNullable(filters)
                        .filter(JsonNode::isObject)
                        .ifPresent(obj -> obj.fieldNames()
                                .forEachRemaining(name -> addScOn(form, name, obj.get(name))));
            } else {
                addScOn(form, key, value);
            }
        }
        return form;
    }

    /**
     * Encodes a single filter entry as one or more TDK <code>scon</code> query values.
     */
    private void addScOn(
            final MultiValueMap<String,String> form,
            final String key,
            final Object rawValue) {

        switch (rawValue) {
            case null -> {
            }
            case Collection<?> list -> list.forEach(v -> form.add("scon", key + ";" + v));
            case Map<?, ?> range -> {
                String min = Optional.ofNullable(range.get("min")).map(Object::toString).orElse("");
                String max = Optional.ofNullable(range.get("max")).map(Object::toString).orElse("");
                form.add("scon", key + ";" + min + "|" + max);
            }
            case JsonNode node when node.isObject() -> {
                String min = node.path("min").asText("");
                String max = node.path("max").asText("");
                form.add("scon", key + ";" + min + "|" + max);
            }
            default -> form.add("scon", key + ";" + rawValue.toString());
        }
    }

}

