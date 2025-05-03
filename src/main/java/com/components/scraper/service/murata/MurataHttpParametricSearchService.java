package com.components.scraper.service.murata;

import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.MpnSearchService;
import com.components.scraper.service.core.ParametricSearchService;
import com.components.scraper.service.core.VendorSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <h2>MurataHttpParametricSearchService</h2>
 *
 * <p>Pure‑HTTP implementation of Murata’s <em>parametric / filter</em> search.
 * The service mimics the calls the public Murata product finder performs:
 * it hits the <b>{@code /webapi/PsdispRest}</b> end‑point, decodes the JSON
 * grid and returns a canonical list of result maps.</p>
 *
 * <p>All Murata‑specific quirks (base URL, {@code cate} mapping, header
 * semantics) are injected through Murata while the
 * grid‑to‑POJO conversion is delegated to {@link JsonGridParser}
 * (provider‑specific implementation).</p>
 *
 * <p>The class is <b>thread‑safe</b>; no mutable state is stored between
 * calls.</p>
 */
@Slf4j
@Service("murataParamSvc")
public class MurataHttpParametricSearchService
        extends VendorSearchEngine implements ParametricSearchService {

    private final JsonGridParser parser;

    public MurataHttpParametricSearchService(
            @Qualifier("murataGridParser") JsonGridParser parser,
            VendorConfigFactory factory,
            RestClient client
    ) {
        super(factory.forVendor("murata"), client);
        this.parser = parser;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Map<String, Object>> searchByParameters(
            @NonNull String category,
            @Nullable String subcategory,
            @Nullable Map<String, Object> parameters,
            int        maxResults

    ) {

        // 1) Resolve cate code from category path
        String cate = resolveCate(category, subcategory);

        MultiValueMap<String, String> q = buildQueryParams(cate, parameters);

        JsonNode root = safeGet(ub -> buildUri(
                getCfg().getBaseUrl(),
                getCfg().getParametricSearchUrl(),
                q));

        if (root == null) return List.of();

        // 5) Parse Murata grid
        List<Map<String, Object>> rows = parser.parse(root.path("Result"));

        // 6) Respect maxResults and return
        return rows.size() > maxResults
                ? rows.subList(0, maxResults)
                : rows;
    }


    /* ================================================================== */
    /*  helpers                                                           */
    /* ================================================================== */

    /** Compose endpoint URI. */
    private URI buildUri(String cate, String scon, int rows) {

        UriBuilder ub = UriComponentsBuilder
                .fromUriString(getCfg().getBaseUrl())          // e.g. https://www.murata.com
                .path(getCfg().getParametricSearchUrl());           // e.g. /webapi/PsdispRest

        MultiValueMap<String,String> qp = new LinkedMultiValueMap<>();
        qp.add("cate",  cate);
        qp.add("partno","");         // blank = no direct MPN restriction
        qp.add("stype", "1");
        if (!scon.isBlank()) qp.add("scon", scon);
        qp.add("lang",  "en-us");
        qp.add("rows",  String.valueOf(rows));

        return ub.queryParams(qp).build(true);    // ‘true’ → skip encoding (%7C already OK)
    }


    /**
     * Build Murata query parameters, incl. proprietary <strong>scon</strong>
     * filter string.
     */
    private MultiValueMap<String, String>
    buildQueryParams(String cate, @Nullable Map<String, Object> filters) {

        var q = new LinkedMultiValueMap<String, String>();
        q.add("cate",  cate);
        q.add("partno", "");          // empty string means “parametric mode”
        q.add("stype", "1");
        q.add("lang",  "en-us");

        if (filters != null && !filters.isEmpty()) {
            String scon = toScon(cate, filters);
            q.add("scon", scon);
        }
        return q;
    }

    /**
     * Convert friendly filter map into Murata’s <code>scon</code> grammar.
     *
     * <pre>
     * { "capacitance": {"min":10,"max":125},
     *   "ratedVoltage": [16,25],
     *   "shape": "0402" }
     *
     * ⟶  "ceramicCapacitors-capacitance;10%7C125|ratedVoltage;16%7C25|shape;0402"
     * </pre>
     */
    private String toScon(String cate,
                          Map<String, Object> filters) {

        String prefix = cate.toLowerCase(Locale.ROOT).replace("lu", "");
        return filters.entrySet()
                .stream()
                .map(e -> encodeOne(prefix, e.getKey(), e.getValue()))
                .collect(Collectors.joining("|"));
    }

    /** encode single k‑v in Murata format */
    private String encodeOne(String prefix, String param, Object v) {

        List<String> values = switch (v) {
            case Map<?,?> m   -> encodeRange(m);
            case Collection<?> c -> c.stream()
                    .map(Object::toString)
                    .toList();
            default            -> List.of(v.toString());
        };

        String joined = String.join("%7C", values);   // “|” must be kept escaped
        return (prefix + "-" + param + ";" + joined);
    }

    /** helper for {"min":x,"max":y} maps */
    private List<String> encodeRange(Map<?,?> m) {

        Object min = m.get("min");
        Object max = m.get("max");

        if (min == null && max == null)
            return List.of();                   // nothing to encode

        if (min == null) return List.of(max.toString());
        if (max == null) return List.of(min.toString());

        return List.of(min + "%7C" + max);      // already escaped “|”
    }

    /**
     * Create Murata’s <kbd>scon</kbd> string from caller‑provided parameter map.
     * <ul>
     *   <li><b>Scalar:</b> {@code name;value}</li>
     *   <li><b>Multi‑value list:</b> {@code name;v1|v2|v3}</li>
     *   <li><b>Range (min/max):</b> {@code name;min|max}</li>
     * </ul>
     * Multiple filters are concatenated with {@code &}.
     */
    private String encodeFilters(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "";

        List<String> terms = new ArrayList<>();

        params.forEach((key, raw) -> {
            if (raw instanceof Map<?,?> rng) {
                Object min = rng.get("min");
                Object max = rng.get("max");
                terms.add(key + ";" + min + "|" + max);
            }
            else if (raw instanceof Collection<?> col) {
                String joined = String.join("|",
                        col.stream().map(Object::toString).toList());
                terms.add(key + ";" + joined);
            }
            else {
                terms.add(key + ";" + raw);
            }
        });
        return String.join("&", terms);
    }

    /** Resolve <i>cate</i> code via YAML mapping – falls back to default. */
    private String resolveCate(String category, String sub) {
        Map<String,String> map = getCfg().getCategories();
        if (map == null || map.isEmpty()) return getCfg().getDefaultCate();

        String path = (category + (sub == null ? "" : "/" + sub)).toUpperCase(Locale.ROOT);

        // longest matching prefix wins
        return map.keySet()
                .stream()
                .filter(path::startsWith)
                .max(Comparator.comparingInt(String::length))
                .map(map::get)
                .orElse(getCfg().getDefaultCate());
    }
}
