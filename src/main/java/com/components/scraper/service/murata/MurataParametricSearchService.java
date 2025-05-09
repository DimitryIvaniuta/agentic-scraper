package com.components.scraper.service.murata;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.ParametricFilterConfig;
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

    /**
     * Component responsible for translating human-readable category or subcategory
     * labels into the exact Murata <code>cate</code> lookup codes.
     * <p>For example, given inputs like <code>"Capacitors"</code> or
     * <code>"Ceramic Capacitors(SMD)"</code>, this resolver returns
     * <code>"luCeramicCapacitorsSMD"</code>.</p>
     */
    private final MurataCateResolver cateResolver;

    /**
     * Constructs a new MurataParametricSearchService.
     *
     * @param parser       the grid‐parser bean (must be qualified as "murataGridParser")
     * @param factory      used to look up vendor‐specific configuration
     * @param client       a preconfigured {@link RestClient} for HTTP calls
     * @param llmHelper    a preconfigured {@link RestClient} for HTTP
     * @param filterConfig YAML-bound filter definitions
     * @param cateResolver a category translate service
     */
    public MurataParametricSearchService(
            @Qualifier("murataGridParser") final JsonGridParser parser,
            final VendorConfigFactory factory,
            final RestClient client,
            final LLMHelper llmHelper,
            final ParametricFilterConfig filterConfig,
            final MurataCateResolver cateResolver
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
     * @param category        the main category (never blank)
     * @param sub             an optional subcategory (may be {@code null})
     * @param defaultCategory the fallback cate value
     * @return the Murata <code>cate</code> lookup code
     */
    private String resolveCate(final String category, final String sub, final String defaultCategory) {
        Map<String, String> map = getCfg().getCategories();
        if (map == null || map.isEmpty()) {
            return Optional.ofNullable(defaultCategory).orElse(getCfg().getDefaultCate());
        }
        final String normalisedPath = (category
                + (StringUtils.isBlank(sub) ? "" : PATH_DELIMITER + sub))
                .toLowerCase(Locale.ROOT)
                .trim();
        // longest matching prefix wins
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
     * @param cate   the category code
     * @param params map of filters (keys → scon fields, values → various shapes)
     * @param rows   max rows to request
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
     * <p>
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
     * @param params mutable request-parameter map received from the REST layer;
     *               used only for reading the optional {@code "details"} entry
     * @param defs   YAML-backed filter definitions for the current <em>cate</em>;
     *               needed to translate UI captions to Murata API field names
     * @param b      the {@link UriBuilder} that is being assembled for the
     *               final HTTP request – this method appends additional
     *               {@code scon=} query parameters to it
     */
    private void buildDetailsQueryParameters(final Map<String, Object> params,
                                             final ParametricFilterConfig.CategoryFilters defs,
                                             final UriBuilder b) {

        /* 1—short-circuit if “details” is missing or blank */
        Object detailsObj = params.get("details");
        if (detailsObj == null || StringUtils.isBlank(detailsObj.toString())) {
            return;
        }

        /* 2—LLM classification */
        JsonNode aiJson = llmHelper.classify(detailsObj.toString());
        if (aiJson == null || !aiJson.isObject()) {
            return;
        }

        /* 3—one-time caption → API-field map for the current cate */
        Map<String, String> captionToParam =
                defs.getFilters().stream()
                        .collect(Collectors.toMap(ParametricFilterConfig.FilterDef::getCaption,
                                ParametricFilterConfig.FilterDef::getParam));

        /* 4—iterate over LLM keys and mutate the URI builder */
        aiJson.fields().forEachRemaining(entry ->
                handleAiEntry(entry, captionToParam, b));
    }

    /**
     * Processes a single AI-derived filter entry by resolving its human-readable caption
     * to the corresponding Murata API field name and adding the appropriate
     * <code>scon</code> query parameters to the URI builder.
     *
     * <p>The resolution is performed via {@link #resolveApiField(String, Map)},
     * which first attempts an exact caption lookup and then falls back to
     * a word‐set comparison if needed.  Any captions without a matching API
     * field are logged and ignored.</p>
     *
     * @param entry            a map entry from the AI output JSON,
     *                         where the key is the UI caption (e.g. "Rated Voltage DC")
     *                         and the value is the filter value node
     * @param captionToParam   mapping from UI captions to Murata API <code>scon</code> field names
     * @param uriBuilder       the <code>UriBuilder</code> used to accumulate query parameters;
     *                         for each resolved field, one or more <code>scon</code> params
     *                         will be appended via <code>queryParam("scon", ...)</code>
     */
    private void handleAiEntry(final Map.Entry<String, JsonNode> entry,
                               final Map<String, String> captionToParam,
                               final UriBuilder uriBuilder) {

        String caption = entry.getKey();
        JsonNode value = entry.getValue();

        String field = resolveApiField(caption, captionToParam);
        if (field == null) {
            log.warn("No scon field mapping for caption “{}” (ignored)", caption);
            return;
        }
        renderScon(field, value).forEach(scon -> uriBuilder.queryParam("scon", scon));
    }

    /**
     * Resolves a UI filter caption to its Murata API field name.
     * <p>This method first tries an exact lookup in the provided map.
     * If that fails, it compares the set of words (ignoring order and punctuation)
     * between the requested caption and each map key, returning the first match.</p>
     *
     * @param caption         the human-facing filter label, e.g. "Rated Voltage DC"
     * @param captionToParam  map from exact captions to their Murata API <code>scon</code> names
     * @return the matching API field name (e.g. "ceramicCapacitors-ratedVoltageDC"),
     *         or {@code null} if no mapping could be found
     */
    @Nullable
    private String resolveApiField(final String caption,
                                   final Map<String, String> captionToParam) {

        /* 1 – exact match */
        String field = captionToParam.get(caption);
        if (field != null) {
            return field;
        }

        /* 2 – word-set comparison (e.g. “Rated Voltage DC” vs “DC Rated Voltage”) */
        Set<String> captionTokens = tokens(caption);
        return captionToParam.entrySet()
                .stream()
                .filter(e -> tokens(e.getKey()).equals(captionTokens))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static Set<String> tokens(final String s) {
        return WORD.matcher(s.toLowerCase(Locale.ROOT))
                .results()
                .map(MatchResult::group)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void buildSearchWithParameters(
            final Map<String, Object> params, final ParametricFilterConfig.CategoryFilters defs, final UriBuilder b) {
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
                    });
        });
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
     *   • field;10|10                // single literal
     *   • field;100|200              // range {min,max}
     *   • field;A,field;B            // multi-select list
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

        if (field == null) {
            return Collections.emptyList();
        }

        // 1) simple literal – String, Number, Boolean
        if (raw instanceof CharSequence
                || raw instanceof Number
                || raw instanceof Boolean
                || (raw instanceof JsonNode node && node.isValueNode())) {
            String val = (raw instanceof JsonNode n) ? n.asText() : raw.toString();
            return List.of(field + ';' + val);
        }

        // 2) range supplied as java.util.Map {min,max}
        if (raw instanceof Map<?, ?> range) {
            String min = Optional.ofNullable(range.get("min"))
                    .map(Object::toString)
                    .orElse("");
            String max = Optional.ofNullable(range.get("max"))
                    .map(Object::toString)
                    .orElse("");
            return List.of(field + ';' + min + '|' + max);
        }

        // 3) range supplied as Jackson ObjectNode {"min":…,"max":…}
        if (raw instanceof com.fasterxml.jackson.databind.node.ObjectNode node) {
            String min = Optional.ofNullable(node.get("min"))
                    .map(JsonNode::asText)
                    .orElse("");
            String max = Optional.ofNullable(node.get("max"))
                    .map(JsonNode::asText)
                    .orElse("");
            return List.of(field + ';' + min + '|' + max);
        }

        // 4) multi-select list
        if (raw instanceof Collection<?> col) {
            return col.stream()
                    .map(val -> field + ';' + val)
                    .toList();
        }

        // 5) unsupported value type
        throw new IllegalArgumentException(
                "Unsupported parameter value for scon (" + field + "): "
                        + raw.getClass().getName());
    }
}
