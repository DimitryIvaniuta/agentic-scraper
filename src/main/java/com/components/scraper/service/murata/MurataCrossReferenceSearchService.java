package com.components.scraper.service.murata;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.CrossReferenceSearchService;
import com.components.scraper.service.core.VendorSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * <h2>MurataHttpCrossReferenceSearchService</h2>
 *
 * <p>Light‑weight (non‑Selenium) cross‑reference search that calls the
 * Murata public JSON endpoint
 * {@code /webapi/SearchCrossReference}.  It converts the two grids that
 * come back – <i>other vendor parts</i> and <i>Murata equivalents</i> –
 * into a pair of record‑lists that the REST controller can emit as JSON.</p>
 *
 * <pre>{@code
 *  GET https://www.murata.com/webapi/SearchCrossReference
 *          ?cate=cgInductorscrossreference
 *          &partno=XAL4020152ME
 *          &lang=en-us
 * }</pre>
 *
 * <p>The actual {@code cate} code depends on the requested category.
 * A handful of codes are known; unknown categories fall back to
 * {@code cgInductorscrossreference} so the request never 404s.</p>
 * <p>
 * Performs cross-reference lookups against Murata’s WebAPI endpoint by:
 * <ol>
 *   <li>Determining the appropriate category code from a competitor’s part number</li>
 *   <li>Issuing an HTTP GET to Murata’s <code>PsdispRest</code> API</li>
 *   <li>Parsing the JSON response into two separate tables (competitor vs. Murata)</li>
 *   <li>Augmenting Murata records with direct product detail URLs</li>
 * </ol>
 *
 * <p><b>Thread‑safe</b>: relies only on stateless collaborators supplied
 * by Spring; no mutable state.</p>
 */
@Slf4j
@Service("murataCrossRefSvc")

public class MurataCrossReferenceSearchService
        extends VendorSearchEngine implements CrossReferenceSearchService {

    /**
     * Number of rows to request per API call.
     */
    private static final int DEFAULT_ROWS = 200;

    /**
     * Parser that converts a JSON grid node into a list of maps (row-by-row).
     */
    private final JsonGridParser parser;

    /**
     * Creates an instance configured for Murata.
     *
     * @param parser  JSON grid parser bean qualified "murataGridParser"
     * @param factory factory for vendor configurations
     * @param client  HTTP client for REST calls
     */
    public MurataCrossReferenceSearchService(
            @Qualifier("murataGridParser") final JsonGridParser parser,
            final VendorConfigFactory factory,
            final RestClient client,
            final LLMHelper llmHelper
    ) {
        super(factory.forVendor("murata"), client, llmHelper);
        this.parser = parser;
    }

    /**
     * Searches for Murata cross-reference data given a competitor manufacturer part number.
     * <p>
     * The response is returned as two separate tables in a list:
     * <ul>
     *   <li><strong>competitor</strong> – competitor’s matching products</li>
     *   <li><strong>murata</strong> – Murata’s own matching products</li>
     * </ul>
     *
     * @param competitorMpn the competitor’s MPN to cross-reference (may include “#”)
     * @param categoryPath  human-readable category/subcategory path segments;
     *                      used to choose the correct Murata API category code
     * @return a list of two maps, each with keys:
     * <ul>
     *   <li><code>table</code> – "competitor" or "murata"</li>
     *   <li><code>records</code> – {@link List} of parsed row maps</li>
     * </ul>
     */
    @Override
    public List<Map<String, Object>> searchByCrossReference(final String competitorMpn,
                                                            final List<String> categoryPath) {

        // Determine the Murata category code for the cross-reference API
        String cate = resolveCate(categoryPath);
        String competitorMpnFixed = competitorMpn.replace("#", "").trim();

        // Perform the HTTP GET against Murata’s cross-reference WebAPI
        JsonNode root = safeGet(ub -> {
            URI base = URI.create(getCfg().getBaseUrl());   // e.g. https://www.murata.com

            // apply scheme + host (and port if present)
            ub = ub.scheme(base.getScheme())
                    .host(base.getHost());
            if (base.getPort() != -1) {                     // port is -1 when absent
                ub = ub.port(base.getPort());
            }

            // prepend any path segment that is already part of base‑url
            String basePath = base.getPath();
            if (StringUtils.hasText(basePath) && !"/".equals(basePath)) {
                ub = ub.path(basePath);                     // e.g. "" or "/"
            }

            /* endpoint + query parameters */
            return ub.path(getCfg().getCrossRefUrl())     // /webapi/PsdispRest
                    .queryParam("cate", cate)
                    .queryParam("partno", competitorMpnFixed)
                    .queryParam("stype", 1)
                    .queryParam("pageno", 1)
                    .queryParam("rows", DEFAULT_ROWS)
                    .queryParam("lang", "en-us")
                    .build();
        });

        if (root == null) {
            return Collections.emptyList();
        }

        // Parse the two sections from the JSON response
        List<Map<String, Object>> competitorTbl =
                parseSection(root, "otherPsDispRest");

        List<Map<String, Object>> murataTbl =
                augmentWithDetailUrl(
                        parseSection(root, "murataPsDispRest"));

        // Wrap each table in a canonical output structure
        List<Map<String, Object>> out = new ArrayList<>(2);

        out.add(Map.of(
                "table", "competitor",
                "records", competitorTbl));

        out.add(Map.of(
                "table", "murata",
                "records", murataTbl));

        return out;
    }

    /**
     * Extracts and parses a single grid section from the root JSON node.
     *
     * @param root the root JSON node of the API response
     * @param key  the JSON property name of the section to parse
     * @return parsed list of row maps, or empty list if the section is missing
     */
    private List<Map<String, Object>> parseSection(final JsonNode root, final String key) {
        JsonNode section = root.path(key);
        if (section.isMissingNode()) {
            log.warn("Missing grid section {}", key);
            return Collections.emptyList();
        }
        return parser.parse(section);
    }

    /**
     * Adds a direct Murata product detail URL to each row in the parsed list.
     *
     * @param in the list of row maps containing at least a "Part Number" entry
     * @return the same list, with each map augmented with a "url" key
     */
    private List<Map<String, Object>> augmentWithDetailUrl(final List<Map<String, Object>> in) {
        for (Map<String, Object> row : in) {
            String pn = Optional.ofNullable(row.get("Part Number"))
                    .map(Object::toString)
                    .orElse("");
            String base = pn.replace("#", "");          // strip the trailing ‘#’
            row.put("url",
                    "https://www.murata.com/en-us/products/productdetail?partno="
                            + base + "%23");
        }
        return in;
    }

    /**
     * Resolves a Murata cross-reference category code from a human-readable path.
     * <p>
     * Supports:
     * <ul>
     *   <li>inductor → <code>cgInductorscrossreference</code></li>
     *   <li>capacitor → <code>cgCapacitorscrossreference</code></li>
     *   <li>filter    → <code>cgEMIFilterscrossreference</code></li>
     * </ul>
     * Falls back to <code>cgInductorscrossreference</code> if no match.
     *
     * @param path list of category/subcategory segments
     * @return the Murata-specific category code
     */
    private String resolveCate(final List<String> path) {
        if (path != null && !path.isEmpty()) {
            String p = String.join("/", path).toLowerCase(Locale.ROOT);

            if (p.contains("inductor")) {
                return "cgInductorscrossreference";
            }
            if (p.contains("capacitor")) {
                return "cgCapacitorscrossreference";
            }
            if (p.contains("filter")) {
                return "cgEMIFilterscrossreference";
            }
        }
        // reasonable fall‑back that exists on the site
        return "cgInductorscrossreference";
    }

}
