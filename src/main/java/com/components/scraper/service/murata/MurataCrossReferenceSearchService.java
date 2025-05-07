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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

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

    private static final int DEFAULT_ROWS = 20;

    private final JsonGridParser parser;

    public MurataCrossReferenceSearchService(
            @Qualifier("murataGridParser") final JsonGridParser parser,
            final VendorConfigFactory factory,
            final RestClient client,
            final LLMHelper llmHelper
    ) {
        super(factory.forVendor("murata"), client, llmHelper);
        this.parser = parser;
    }

    @Override
    public List<Map<String, Object>> searchByCrossReference(final String competitorMpn,
                                                            final List<String> categoryPath) {

        // Determine the Murata category code for the cross-reference API
        String cate = discoverCrossRefCate(competitorMpn);
        if(!StringUtils.hasText(cate)) {
            cate = resolveCate(categoryPath);
        }
        final String cateValue = cate;

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
                    .queryParam("cate", cateValue)
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


    private List<Map<String, Object>> parseSection(final JsonNode root, final String key) {
        JsonNode section = root.path(key);
        if (section.isMissingNode()) {
            log.warn("Missing grid section {}", key);
            return Collections.emptyList();
        }
        return parser.parse(section);
    }


    /**
     * Calls Murata’s public site-search JSON API to figure out the best category_id for cross-reference.
     * If the top‐level entry has children, uses the first child’s category_id; else the parent’s.
     * Returns null if no valid category_id was found.
     */
    private String discoverCrossRefCate(String mpn) {
//        MultiValueMap<String,String> q = new LinkedMultiValueMap<>();
//        q.add("op",     "AND");
//        q.add("q",      mpn);
//        q.add("region", "en-us");
//        q.add("src",    "product");
//
//        URI uri = buildUri(
//                getCfg().getBaseUrlSitesearch(),
//                getCfg().getMpnSearchProduct(),
//                q
//        );

        JsonNode root = safeGet(getProductSitesearchUri(mpn));
        if (root == null) {
            log.warn("No response from site-search for Competitor MPN {}", mpn);
            return null;
        }

        JsonNode xrefArray = root.path("crossreference");
        if (xrefArray.isArray() && !xrefArray.isEmpty()) {
            JsonNode first = xrefArray.get(0);

            // look for a child category first
            JsonNode children = first.path("children");
            if (children.isArray() && !children.isEmpty()) {
                String childCate = children.get(0).path("category_id").asText(null);
                if (StringUtils.hasText(childCate)) {
                    log.info("Site-search: using child cross-ref cate '{}' for MPN {}", childCate, mpn);
                    return childCate;
                }
            }

            // fallback to the parent’s category_id
            String parentCate = first.path("category_id").asText(null);
            if (StringUtils.hasText(parentCate)) {
                log.info("Site-search: using parent cross-ref cate '{}' for MPN {}", parentCate, mpn);
                return parentCate;
            }
        }

        log.warn("Site-search returned no crossreference entries for MPN {}", mpn);
        return null;
    }


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
