package com.components.scraper.service.murata;

import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.CrossReferenceSearchService;
import com.components.scraper.service.core.VendorSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.ArrayList;
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
 *
 * <p><b>Thread‑safe</b>: relies only on stateless collaborators supplied
 * by Spring; no mutable state.</p>
 */
@Slf4j
@Service("murataCrossRefSvc")

public class MurataCrossReferenceSearchService
        extends VendorSearchEngine implements CrossReferenceSearchService {

    private final JsonGridParser parser;

    public MurataCrossReferenceSearchService(
            @Qualifier("murataGridParser") JsonGridParser parser,
            VendorConfigFactory factory,
            RestClient client
    ) {
        super(factory.forVendor("murata"), client);
        this.parser = parser;
    }

    @Override
    public List<Map<String, Object>> searchByCrossReference(String competitorMpn,
                                                            List<String> categoryPath) {

        // 1) figure‑out the cate=… code
        String cate = resolveCate(categoryPath);
        String competitorMpnFixed = competitorMpn.replace("#", "").trim();

        // 2) call Murata endpoint
//        JsonNode root = safeGet(ub -> ub
//                .scheme("https").host(getCfg().getBaseUrl())
//                .path(getCfg().getCrossRefUrl())
//                .queryParam("cate",   cate)
//                .queryParam("partno", competitorMpnFixed)
//                .queryParam("stype",  1)
//                .queryParam("lang",   "en-us")
//                .build());

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
                    .queryParam("cate",   cate)
                    .queryParam("partno", competitorMpnFixed)
                    .queryParam("stype",  1)
                    .queryParam("lang",   "en-us")
                    .build();
        });

        // 3) split into the two grids
        List<Map<String, Object>> competitorTbl =
                parseSection(root, "otherPsDispRest");

        List<Map<String, Object>> murataTbl =
                augmentWithDetailUrl(
                        parseSection(root, "murataPsDispRest"));

        // 4) pack into the canonical structure: two tables => two entries
        List<Map<String, Object>> out = new ArrayList<>(2);

        out.add(Map.of(
                "table",   "competitor",
                "records", competitorTbl));

        out.add(Map.of(
                "table",   "murata",
                "records", murataTbl));

        return out;
    }

    /** Parse one of the two grid sections via the dedicated parser. */
    private List<Map<String, Object>> parseSection(JsonNode root, String key) {
        JsonNode section = root.path(key);
        if (section.isMissingNode()) {
            log.warn("Missing grid section {}", key);
            return List.of();
        }
        return parser.parse(section);
    }

    /** Add clickable product‑detail URL to each Murata record. */
    private List<Map<String, Object>> augmentWithDetailUrl(List<Map<String, Object>> in) {
        for (Map<String, Object> row : in) {
            String pn   = Optional.ofNullable(row.get("Part Number"))
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
     * Very small lookup that maps high‑level categories to Murata’s
     * cross‑reference {@code cate} codes.
     */
    private String resolveCate(List<String> path) {
        if (path != null && !path.isEmpty()) {
            String p = String.join("/", path).toLowerCase(Locale.ROOT);

            if (p.contains("inductor")) return "cgInductorscrossreference";
            if (p.contains("capacitor")) return "cgCapacitorscrossreference";
            if (p.contains("filter"))    return "cgEMIFilterscrossreference";
        }
        // reasonable fall‑back that exists on the site
        return "cgInductorscrossreference";
    }

}
