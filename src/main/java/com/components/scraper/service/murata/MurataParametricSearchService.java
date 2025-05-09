package com.components.scraper.service.murata;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.ParametricFilterConfig;
import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.ParametricSearchService;
import com.components.scraper.service.core.VendorSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
     * helper – turn a caption into its set of words, e.g.
     */
    private static final Pattern WORD = Pattern.compile("\\p{Alnum}+");

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
     * All parametric‐filter definitions loaded from YAML.
     */
    private final ParametricFilterConfig filterConfig;

    private final MurataCateResolver cateResolver;

    /**
     * Constructs a new MurataParametricSearchService.
     *
     * @param parser    the grid‐parser bean (must be qualified as "murataGridParser")
     * @param factory   used to look up vendor‐specific configuration
     * @param client    a preconfigured {@link RestClient} for HTTP calls
     * @param llmHelper a preconfigured {@link RestClient} for HTTP
     * @param filterConfig YAML-bound filter definitions
     */
    public MurataParametricSearchService(
            @Qualifier("murataGridParser") final JsonGridParser parser,
            final VendorConfigFactory factory,
            final RestClient client,
            final LLMHelper llmHelper,
            final ParametricFilterConfig filterConfig,
            MurataCateResolver cateResolver
    ) {
        super(factory.forVendor("murata"), client, llmHelper);
        this.parser = parser;
        this.filterConfig = filterConfig;
        this.cateResolver = cateResolver;
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
     * @param category    the main category name (e.g. “Ceramic Capacitors”)
     * @param subcategory an optional subcategory (may be {@code null})
     * @param parameters  a map of filter criteria (never {@code null})
     * @param maxResults  maximum number of rows to return (server may cap)
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
        String cateByMpn = discoverCate(getMpnParam(parameters));
        String cate = resolveCate(category, subcategory, cateByMpn);

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
     * @param defaultCategory       the fallback cate value
     * @return the Murata <code>cate</code> lookup code
     */
    private String resolveCate(final String category, final String sub, final String defaultCategory) {
        Map<String, String> map = getCfg().getCategories();
        if (map == null || map.isEmpty()) {
            return Optional.ofNullable(defaultCategory).orElse(getCfg().getDefaultCate());
        }

//        String path = (category + (sub == null ? "" : PATH_DELIMITER + sub)).toUpperCase(Locale.ROOT);
        final String normalisedPath = (category +                                   // "Capacitors"
                (StringUtils.isBlank(sub) ? "" : PATH_DELIMITER + sub))                        //  + "/Ceramic Capacitors(SMD)"
                .toLowerCase(Locale.ROOT)
                .trim();
        // longest matching prefix wins
//        return map.keySet()
//                .stream()
//                .filter(path::startsWith)
//                .max(Comparator.comparingInt(String::length))
//                .map(map::get)
//                .orElse(Optional.ofNullable(defaultCategory).orElse(getCfg().getDefaultCate()));
        return cateResolver.cateFor(normalisedPath);
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
            // load filter definitions for this category
            ParametricFilterConfig.CategoryFilters defs = filterConfig.getCategoryFilters("murata", cate);

            if (defs != null) {
                buildDetailsQueryParameters(params, defs, b);
                buildSearchWithParameters(params, defs, b);
            }
            return b.build();
        };
    }

    /**
     * Adds extra <strong>details-driven</strong> filter criteria to a Murata
     * <code>productsearch</code> request.
     *
     * <p><b>Background – free-text “details” field</b><br>
     * Clients of the <code>/api/search/parametric</code> endpoint may supply a
     * {@code "details"} attribute instead of – or in addition to – the structured
     * {@code parameters} map.  Example:
     *
     * <pre>
     * {
     *   "category"  : "Capacitors",
     *   "subcategory": "Ceramic Capacitors(SMD)",
     *   "details"   : "the dc rated voltage should be in the range of 100-200 " +
     *                 "and the capacitance no more than 10"
     * }
     * </pre>
     *
     * The sentence is passed to the LLM which returns a JSON object whose <em>keys
     * are UI-captions</em> and whose values are already structured (scalar, list,
     * or <code>{min,max}</code>).  A typical response might be:
     *
     * <pre>
     * {
     *   "Rated Voltage DC" : {"min": 100, "max": 200},
     *   "Capacitance"      : 10
     * }
     * </pre>
     *
     * <p>This helper converts that LLM output into Murata’s <code>scon</code>
     * query-parameters and appends them to the supplied {@link UriBuilder}.</p>
     *
     * <h4>Algorithm</h4>
     * <ol>
     *   <li>Check if a non-blank {@code details} entry is present in {@code params};
     *       exit early otherwise.</li>
     *   <li>Invoke {@link LLMHelper#classify(String)} – the model extracts the
     *       relevant filters.</li>
     *   <li>Build a one-shot <em>caption → API-field</em> map from
     *       <code>defs</code> (the YAML-configured filter definitions).</li>
     *   <li>For every key/value pair returned by the LLM
     *     <ol type="a">
     *       <li>Try to resolve the API field by an exact caption match.</li>
     *       <li>If that fails, compare word sets (so “DC Rated Voltage” equals
     *           “Rated Voltage DC”).</li>
     *       <li>If a mapping is found, delegate to
     *           {@link #renderScon(String, Object)} and append the resulting
     *           <code>scon</code> fragments to the URI.</li>
     *       <li>If no mapping exists, log a warning and ignore the entry.</li>
     *     </ol>
     *   </li>
     * </ol>
     *
     * @param params  mutable request-parameter map received from the REST layer;
     *                used only for reading the optional {@code "details"} entry
     * @param defs    YAML-backed filter definitions for the current <em>cate</em>;
     *                needed to translate UI captions to Murata API field names
     * @param b       the {@link UriBuilder} that is being assembled for the
     *                final HTTP request – this method appends additional
     *                {@code scon=} query parameters to it
     */
    private void buildDetailsQueryParameters(Map<String, Object> params, ParametricFilterConfig.CategoryFilters defs, UriBuilder b) {
        // "details": "the dc rated voltage should be in the range of 100-200 and the capacitance no more than 10"
        Object details = params.get("details");
        if (details != null && StringUtils.isNotBlank(details.toString())) {
            JsonNode aiJson = llmHelper.classify(details.toString());
            if (aiJson != null && aiJson.isObject()) {
                // caption → param map (only once per request)
                Map<String, String> captionToParam = defs.getFilters().stream()
                        .collect(Collectors.toMap(ParametricFilterConfig.FilterDef::getCaption,
                                ParametricFilterConfig.FilterDef::getParam));
                aiJson.fields().forEachRemaining(e -> {
                    String caption = e.getKey();              // e.g. "DC Rated Voltage"
                    JsonNode value = e.getValue();

                    /* 1) try direct lookup ------------------------------------------------ */
                    String param = captionToParam.get(caption);

                    /* 2) if not found, compare by word-set -------------------------------- */
                    if (param == null) {
                        Set<String> captionWords = tokens(caption);

                        for (Map.Entry<String, String> entry : captionToParam.entrySet()) {
                            if (tokens(entry.getKey()).equals(captionWords)) {
                                param = entry.getValue();
                                break;
                            }
                        }
                    }
                    /* 3) finally, add to the parameter map -------------------------------- */
                    if (param != null) {
                        renderScon(param, value)
                                .forEach(s -> {
                                    b.queryParam("scon", s);
                                });
                    } else {
                        log.warn("No scon field mapping for caption “{}” (ignored)", caption);
                    }
                });
            }
        }
    }

    private static Set<String> tokens(String s) {
        return WORD.matcher(s.toLowerCase(Locale.ROOT))
                .results()
                .map(MatchResult::group)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void buildSearchWithParameters(Map<String, Object> params, ParametricFilterConfig.CategoryFilters defs, UriBuilder b) {
        Map<String, String> captionToParam = defs.getFilters()
                .stream()
                .collect(Collectors.toMap(
                        ParametricFilterConfig.FilterDef::getCaption,
                        ParametricFilterConfig.FilterDef::getParam
                ));
        params.forEach((key, value) -> {

            renderScon(captionToParam.get(key), value)
                .forEach(s -> {
                    b.queryParam("scon", s);
                });});
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
//    private List<String> renderScon(final String field, final Object raw) {
//        if (field == null) {
//            return Collections.emptyList();
//        }
//        var sb = new StringBuilder(field).append(';');
//
//        /* ---------- single literal ------------------------------------------------ */
//        if (raw instanceof CharSequence || raw instanceof Number) {
//            return List.of(sb.append(raw).toString());
//        }
//
//        /* ---------- range {min,max} ------------------------------------------------ */
//        if (raw instanceof Map<?, ?> range) {                 // we already know the shape
//            // ← explicit, safe cast
//
//            String min = Optional.ofNullable(range.get("min"))
//                    .map(Object::toString)
//                    .orElse("");
//
//            String max = Optional.ofNullable(range.get("max"))
//                    .map(Object::toString)
//                    .orElse("");
//
//            return List.of(sb.append(min).append('|').append(max).toString());
//        }
//
//        /* ---------- multi‑value list ---------------------------------------------- */
//        if (raw instanceof Collection<?> col) {
//            return col.stream()
//                    .map(val -> field + ';' + val)
//                    .toList();
//        }
//        throw new IllegalArgumentException(
//                "Unsupported parameter value for scon: " + raw.getClass());
//    }

    /**
     * Builds one or more “scon=” fragments for the given filter field.
     *
     * <pre>
     * field;10|10                // single literal
     * field;100|200              // range {min,max}
     * field;A,field;B            // multi-select list
     * </pre>
     *
     * @param field the Murata filter key (e.g. {@code ceramicCapacitors-capacitance})
     * @param raw   the value supplied by the caller (scalar, list, or range)
     * @return list of fully-formed {@code scon} strings
     */
    private List<String> renderScon(final String field, final Object raw) {

        if (field == null) {
            return Collections.emptyList();
        }

        /* --------------------------------------------------------------------- */
        /* 1) simple literal – String, Number, Boolean                           */
        /* --------------------------------------------------------------------- */
        if (raw instanceof CharSequence
                || raw instanceof Number
                || raw instanceof Boolean
                || (raw instanceof JsonNode node && node.isValueNode())) {
//            return List.of(field + ';' + raw);
            String val = (raw instanceof JsonNode n) ? n.asText() : raw.toString();
            return List.of(field + ';' + val);
        }

        /* --------------------------------------------------------------------- */
        /* 2) range supplied as java.util.Map {min,max}                          */
        /* --------------------------------------------------------------------- */
        if (raw instanceof Map<?, ?> range) {
            String min = Optional.ofNullable(range.get("min"))
                    .map(Object::toString)
                    .orElse("");
            String max = Optional.ofNullable(range.get("max"))
                    .map(Object::toString)
                    .orElse("");
            return List.of(field + ';' + min + '|' + max);
        }

        /* --------------------------------------------------------------------- */
        /* 3) range supplied as Jackson ObjectNode {"min":…,"max":…}             */
        /* --------------------------------------------------------------------- */
        if (raw instanceof com.fasterxml.jackson.databind.node.ObjectNode node) {
            String min = Optional.ofNullable(node.get("min"))
                    .map(JsonNode::asText)
                    .orElse("");
            String max = Optional.ofNullable(node.get("max"))
                    .map(JsonNode::asText)
                    .orElse("");
            return List.of(field + ';' + min + '|' + max);
        }

        /* --------------------------------------------------------------------- */
        /* 4) multi-select list                                                  */
        /* --------------------------------------------------------------------- */
        if (raw instanceof Collection<?> col) {
            return col.stream()
                    .map(val -> field + ';' + val)
                    .toList();
        }

        /* --------------------------------------------------------------------- */
        /* 5) unsupported value type                                             */
        /* --------------------------------------------------------------------- */
        throw new IllegalArgumentException(
                "Unsupported parameter value for scon (" + field + "): "
                        + raw.getClass().getName());
    }


}
