package com.components.scraper.service.tdk;

import com.components.scraper.config.VendorCfg;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.ParametricSearchService;
import com.components.scraper.service.core.VendorSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
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

    private final JsonGridParser gridParser;

    public TdkParametricSearchService(VendorCfg cfg,
                                          RestClient client,
                                          JsonGridParser gridParser) {
        super(cfg, client);
        this.gridParser = gridParser;
    }

    /* ------------------------------------------------------------------ */
    /*  Public API                                                        */
    /* ------------------------------------------------------------------ */

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> searchByParameters(@NonNull  String category,
                                                        @Nullable String subcategory,
                                                        @Nullable Map<String, Object> parameters,
                                                        int        maxResults) {

        MultiValueMap<String, String> q = buildQueryParams(category, subcategory, parameters);

        JsonNode root = safeGet(ub -> buildUri(
                getCfg().getBaseUrl(),            // e.g. https://product.tdk.com
                getCfg().getParametricSearchUrl(),     // e.g. /en/search/productsearch
                q));

        if (root == null) return List.of();

        List<Map<String, Object>> rows = gridParser.parse(root);

        return rows.size() > maxResults
                ? rows.subList(0, maxResults)
                : rows;
    }

    /* ------------------------------------------------------------------ */
    /*  helpers                                                           */
    /* ------------------------------------------------------------------ */

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
    private MultiValueMap<String, String>
    buildQueryParams(String category,
                     @Nullable String sub,
                     @Nullable Map<String, Object> filters) {

        var q = new LinkedMultiValueMap<String, String>();

        q.add("cat", category);
        if (StringUtils.isNotBlank(sub))
            q.add("sub", Objects.requireNonNull(sub).trim());

        if (filters != null && !filters.isEmpty())
            q.add("fn", encodeFilters(filters));

        /* localisation – TDK understands ISO‑lang plus “filter” flag */
        q.add("lang", "en");

        return q;
    }

    /* ------------------------------------------------------------------ */

    /** Encode caller‑friendly filter map into TDK’s <code>fn</code> string. */
    private String encodeFilters(Map<String, Object> filters) {

        return filters.entrySet().stream()
                .map(e -> encodeOne(e.getKey(), e.getValue()))
                .collect(Collectors.joining("|"));
    }

    /** Encode a single parameter */
    private String encodeOne(String name, Object rawVal) {

        String encodedValues;

        switch (rawVal) {
            case Map<?,?> m -> {
                String min = Objects.toString(m.get("min"), "");
                String max = Objects.toString(m.get("max"), "");
                encodedValues = min + "-" + max;        // “‑” separator
            }
            case Collection<?> c -> {
                encodedValues = c.stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(","));
            }
            default -> encodedValues = rawVal.toString();
        }

        /* RFC‑3986 encode (TDK rejects ‘%20’; keep “|” unencoded). */
        return URLEncoder.encode(name + ":" + encodedValues, StandardCharsets.UTF_8)
                .replace("%7C", "|");
    }

}
