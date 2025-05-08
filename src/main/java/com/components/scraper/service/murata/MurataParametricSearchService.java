package com.components.scraper.service.murata;

import com.components.scraper.ai.LLMHelper;
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
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * <h2>Murata – Parametric search</h2>
 *
 * <p>Calls <code>/webapi/PsdispRest</code> directly (no Selenium) and turns the
 * JSON “grid” response into a list&nbsp;of {@code Map&lt;String,Object&gt;}
 * where keys are the human‑readable column titles.</p>
 *
 * <p>The service supports:</p>
 * <ul>
 *   <li>automatic <code>cate</code> lookup from MPN prefix,</li>
 *   <li>unlimited parameter filters via the Murata <code>scon</code> syntax,</li>
 *   <li>row limiting (<code>rows</code>) and server‑side sorting.</li>
 * </ul>
 */
@Slf4j
@Service("murataParamSvc")
public class MurataParametricSearchService
        extends VendorSearchEngine implements ParametricSearchService {

    /**
     * Delimiter used to separate category and subcategory path segments.
     */
    private static final String PATH_DELIMITER = "/";

    /**
     * The injected JSON‐grid parser that transforms a Murata JSON payload
     * into a list of row‐maps.
     */
    private final JsonGridParser parser;

    /**
     * Constructs a new MurataParametricSearchService.
     *
     * @param parser    the grid‐parser bean (must be qualified as "murataGridParser")
     * @param factory   used to look up vendor‐specific configuration
     * @param client    a preconfigured {@link RestClient} for HTTP calls
     * @param llmHelper a preconfigured {@link RestClient} for HTTP
     */
    public MurataParametricSearchService(
            @Qualifier("murataGridParser") final JsonGridParser parser,
            final VendorConfigFactory factory,
            final RestClient client,
            final LLMHelper llmHelper
    ) {
        super(factory.forVendor("murata"), client, llmHelper);
        this.parser = parser;
    }

    /**
     * Performs a parametric search for Murata parts.
     *
     * <p>The supplied <code>parameters</code> map may contain:</p>
     * <ul>
     *   <li>Single‐value entries (Number or CharSequence),</li>
     *   <li>Range maps with keys "min" and/or "max",</li>
     *   <li>Collections for multi‐select filters.</li>
     * </ul>
     *
     * <p>Expected <u>parameters</u> map shapes:</p>
     * <pre>
     *  "capacitance"                 : 10                      // single value
     *  "ratedVoltageDC"              : { "min": 100, "max": 200 }
     *  "characteristic"              : ["C0G", "X7R"]          // multi‑select
     * </pre>
     *
     * @param category     the main category name (e.g. “Ceramic Capacitors”)
     * @param subcategory  an optional subcategory (may be {@code null})
     * @param parameters   a map of filter criteria (never {@code null})
     * @param maxResults   maximum number of rows to return (server may cap)
     * @return a list of result‐row maps; each map’s keys are column titles
     * @throws IllegalArgumentException if any parameter value type is unsupported
     */
    @Override
    public List<Map<String, Object>> searchByParameters(
            @NonNull final String category,
            @Nullable final String subcategory,
            @NonNull final Map<String, Object> parameters,
            final int maxResults

    ) {

        // 1) Resolve cate code from mpn
        String cate = discoverCate(getMpnParam(parameters));
        if (StringUtils.isBlank(cate)) {
            cate = resolveCate(category, subcategory);
        }

        JsonNode root = safeGet(uriBuilder(cate, parameters, maxResults));
        if (root == null) {
            return Collections.emptyList();
        }

        // 5) Parse Murata grid
        List<Map<String, Object>> rows = parser.parse(root);

        // 6) Respect maxResults and return
        return rows.size() > maxResults
                ? rows.subList(0, maxResults)
                : rows;
    }

    /**
     * Combines category and optional subcategory into the Murata‐internal “path”
     * string, then looks up the corresponding <code>cate</code> code from YAML
     * mappings.  The longest matching prefix wins; if none match, returns the
     * vendor’s default category.
     *
     * @param category  the main category (never blank)
     * @param sub       an optional subcategory (may be {@code null})
     * @return the Murata <code>cate</code> lookup code
     */
    private String resolveCate(final String category, final String sub) {
        Map<String, String> map = getCfg().getCategories();
        if (map == null || map.isEmpty()) {
            return getCfg().getDefaultCate();
        }

        String path = (category + (sub == null ? "" : PATH_DELIMITER + sub)).toUpperCase(Locale.ROOT);

        // longest matching prefix wins
        return map.keySet()
                .stream()
                .filter(path::startsWith)
                .max(Comparator.comparingInt(String::length))
                .map(map::get)
                .orElse(getCfg().getDefaultCate());
    }

    /**
     * As Murata repeats {@code scon=} for every filter, we must build an URI
     * with multiple identical query‑parameter keys.  This helper returns an
     * {@link UriBuilder}‑&gt;{@link URI} lambda ready for {@code RestClient}.
     * Builds a URI‐factory function which adds:
     * <ul>
     *   <li>Repeated <code>scon</code> query parameters (one per filter),</li>
     *   <li>Standard Murata params: <code>stype=1</code>, <code>lang=en-us</code>, etc.</li>
     * </ul>
     *
     * @param cate     the category code
     * @param params   map of filters (keys → scon fields, values → various shapes)
     * @param rows     max rows to request
     * @return a lambda suitable for {@link #safeGet(Function)}
     */
    private Function<UriBuilder, URI> uriBuilder(final String cate,
                                                 final Map<String, Object> params,
                                                 final int rows) {

        return ub -> {

            URI base = URI.create(getCfg().getBaseUrl());   // e.g. https://www.murata.com

            // apply scheme + host (and port if present)
            ub = ub.scheme(base.getScheme())
                    .host(base.getHost());
            if (base.getPort() != -1) {                     // port is -1 when absent
                ub = ub.port(base.getPort());
            }

            // prepend any path segment that is already part of base‑url
            String basePath = base.getPath();
            if (org.springframework.util.StringUtils.hasText(basePath) && !"/".equals(basePath)) {
                ub = ub.path(basePath);                     // e.g. "" or "/"
            }

            UriBuilder b = ub
                    .path(getCfg().getParametricSearchUrl())    // usually /webapi/PsdispRest
                    .queryParam("cate", cate)
                    .queryParam("partno", getMpnParam(params))
                    .queryParam("stype", 1)
                    .queryParam("rows", rows)
                    .queryParam("lang", "en-us");

            /* add one “scon” parameter per filter */
            params.forEach((key, value) -> renderScon(key, value)
                    .forEach(s -> b.queryParam("scon", s)));

            return b.build();
        };
    }

    private String getMpnParam(final Map<String, Object> params) {
        return Optional.ofNullable(params.get("mpn"))
                .map(Object::toString).orElse(null);
    }

    /**
     * Calls Murata’s public site‐search JSON endpoint to discover the first
     * matching <code>category_id</code> for the given MPN. Prefers a child
     * category if present; otherwise falls back to the parent category.
     *
     * @param mpn the manufacturer part number to search (must be at least 3 characters)
     * @return the discovered <code>category_id</code>, or {@code null} if none found
     */
    private String discoverCate(final String mpn) {
        if (StringUtils.isBlank(mpn) || mpn.length() < PARTNO_PREFIX_LENGTH) {
            return null;
        }
        JsonNode resp = safeGet(getProductSitesearchUri(mpn));
        if (resp == null) {
            log.warn("No response from site-search for Competitor MPN {}", mpn);
            return null;
        }
        JsonNode cats = resp.path("categories");
        if (cats.isArray() && !cats.isEmpty()) {
            JsonNode first = cats.get(0);
            JsonNode children = first.path("children");
            if (children.isArray() && !children.isEmpty()) {
                String childCate = children.get(0).path("category_id").asText(null);
                if (org.springframework.util.StringUtils.hasText(childCate)) {
                    log.info("Using child category '{}' for MPN {}", childCate, mpn);
                    return childCate;
                }
            }
            // no valid child, fall back to parent
            String parentCate = first.path("category_id").asText(null);
            if (org.springframework.util.StringUtils.hasText(parentCate)) {
                log.info("Using parent category '{}' for MPN {}", parentCate, mpn);
                return parentCate;
            }
        }

        log.warn("Murata site‐search returned no categories for MPN {}", mpn);
        return null;
    }

    /**
     * Build one “scon=” clause.
     *
     * <pre>
     *   • single value            -> field;10|10
     *   • range  {"min":x,"max":y}-> field;x|y
     *   • list   [a,b,c]          -> field;a,b,c
     * </pre>
     * <p>
     * Murata does not care which order the scon parameters appear in, it just OR‑s
     * them together.  So caller can concatenate the list with ‘&’.
     * Renders one or more Murata <code>scon</code> filters from a raw value.
     *
     * <ul>
     *   <li>If the value is a {@link Number} or {@link CharSequence}, emits
     *       exactly one clause: <code>field;value</code>.</li>
     *   <li>If the value is a {@link Map} with keys <code>"min"</code> and/or
     *       <code>"max"</code>, emits: <code>field;min|max</code>,
     *       using empty string if one side is missing.</li>
     *   <li>If the value is a {@link Collection}, emits one clause
     *       <code>field;item</code> per element.</li>
     * </ul>
     *
     * @param field  the internal Murata field name for this filter
     * @param raw    the raw filter value (scalar, range‐map, or collection)
     * @return a list of properly‐encoded <code>scon</code> query strings
     * @throws IllegalArgumentException if the raw type is unsupported
     */
    private List<String> renderScon(final String field, final Object raw) {

        var sb = new StringBuilder(field).append(';');

        /* ---------- single literal ------------------------------------------------ */
        if (raw instanceof CharSequence || raw instanceof Number) {
            return List.of(sb.append(raw).toString());
        }

        /* ---------- range {min,max} ------------------------------------------------ */
        if (raw instanceof Map<?, ?> range) {                 // we already know the shape
            // ← explicit, safe cast

            String min = Optional.ofNullable(range.get("min"))
                    .map(Object::toString)
                    .orElse("");

            String max = Optional.ofNullable(range.get("max"))
                    .map(Object::toString)
                    .orElse("");

            return List.of(sb.append(min).append('|').append(max).toString());
        }

        /* ---------- multi‑value list ---------------------------------------------- */
        if (raw instanceof Collection<?> col) {
            return col.stream()
                    .map(val -> field + ';' + val)
                    .toList();
        }
        throw new IllegalArgumentException(
                "Unsupported parameter value for scon: " + raw.getClass());
    }
}
