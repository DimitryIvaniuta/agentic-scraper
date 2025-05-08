package com.components.scraper.service.tdk;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.VendorCfg;
import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.ParametricSearchService;
import com.components.scraper.service.core.VendorSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * <h2>TDK HTTP Parametric Search Service</h2>
 *
 * <p>Pure‑HTTP implementation of TDK’s parametric/grid search endpoint
 * (<kbd>{base‑url}{parametric‑path}</kbd>; e.g.
 * <code>https://product.tdk.com/en/search/productsearch</code>).</p>
 *
 * <p>The service converts a high‑level filter map into TDK’s proprietary
 * <code>fn</code> query parameter (documented by TDK as
 * “<i>filter‑name</i>”) and maps the returned JSON grid into a
 * <code>List&lt;Map&lt;String,Object&gt;&gt;</code>.</p>
 *
 * <p>Design principles are identical to the Murata counterpart:</p>
 * <ul>
 *   <li>No vendor‑specific parser code lives here – grid unpacking is
 *       delegated to {@link JsonGridParser}.</li>
 *   <li>All URL bits come from {@link VendorCfg} so that nothing is
 *       hard‑coded in Java.</li>
 *   <li>Error handling/fallback are done once in {@link VendorSearchEngine}.
 *   </li>
 * </ul>
 */
@Slf4j
@SuppressWarnings("DuplicatedCode")   // intentional symmetry with Murata class
public class TdkParametricSearchService
        extends VendorSearchEngine
        implements ParametricSearchService {

    private final JsonGridParser parser;

    public TdkParametricSearchService(
            @Qualifier("tdkGridParser") final JsonGridParser parser,
            final VendorConfigFactory factory,
            final RestClient client,
            final LLMHelper llmHelper
    ) {
        super(factory.forVendor("tdk"), client, llmHelper);
        this.parser = parser;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Map<String, Object>> searchByParameters(@NonNull final String category,
                                                        @Nullable final String subcategory,
                                                        @Nullable final Map<String, Object> parameters,
                                                        final int maxResults) {

        MultiValueMap<String, String> q = buildQueryParams(category, subcategory, parameters);

        JsonNode root = safeGet(ub -> buildUri(
                getCfg().getBaseUrl(),            // e.g. https://product.tdk.com
                getCfg().getParametricSearchUrl(),     // e.g. /en/search/productsearch
                q));

        if (root == null) {
            return List.of();
        }

        List<Map<String, Object>> rows = parser.parse(root);

        return rows.size() > maxResults
                ? rows.subList(0, maxResults)
                : rows;
    }

    /**
     * Assemble TDK query parameters.
     *
     * <p>The current public spec (2024‑2025) looks like:
     * <pre> base‑url + parametric‑path
     *      ?cat=<category>
     *      &sub=<subcategory>
     *      &fn=<filter1>|<filter2>|...</pre>
     * Each filter string itself is composed as
     * <pre>parameterName:value1,value2…</pre>
     * (comma for multiple values, dash “min‑max” for ranges).
     */
    private MultiValueMap<String, String> buildQueryParams(
            final String category,
            @Nullable final String sub,
            @Nullable final Map<String, Object> filters) {
        var q = new LinkedMultiValueMap<String, String>();

        q.add("cat", category);
        if (StringUtils.isNotBlank(sub)) {
            q.add("sub", Objects.requireNonNull(sub).trim());
        }

        if (filters != null && !filters.isEmpty()) {
            q.add("fn", encodeFilters(filters));
        }

        /* localisation – TDK understands ISO‑lang plus “filter” flag */
        q.add("lang", "en");

        return q;
    }

    /**
     * Encode caller‑friendly filter map into TDK’s <code>fn</code> string.
     */
    private String encodeFilters(final Map<String, Object> filters) {

        return filters.entrySet().stream()
                .map(e -> encodeOne(e.getKey(), e.getValue()))
                .collect(Collectors.joining("|"));
    }

    /**
     * Encodes a single filter into Murata’s “scon” syntax, then URL-encodes
     * per RFC-3986 (preserving “|”).
     *
     * @param name   the filter field name
     * @param rawVal the filter value, which may be:
     *               <ul>
     *                 <li>a {@link Map} with keys "min" and/or "max" → "min-max"</li>
     *                 <li>a {@link Collection} → comma-joined list</li>
     *                 <li>anything else → {@code toString()}</li>
     *               </ul>
     * @return an RFC-3986 encoded string of the form "{@code name:encodedValues}"
     */
    private String encodeOne(final String name, final Object rawVal) {
        String encodedValues = switch (rawVal) {
            case Map<?, ?> m -> {
                String min = Objects.toString(m.get("min"), "");
                String max = Objects.toString(m.get("max"), "");
                yield min + "-" + max;
            }
            case Collection<?> c -> c.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(","));
            default -> Objects.toString(rawVal, "");
        };

        // Build the raw "name:values" string, then percent-encode (but keep "|" literal)
        String raw = name + ":" + encodedValues;
        return URLEncoder
                .encode(raw, StandardCharsets.UTF_8)
                .replace("%7C", "|");
    }

}
