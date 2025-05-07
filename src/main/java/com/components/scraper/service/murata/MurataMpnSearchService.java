package com.components.scraper.service.murata;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.VendorSearchEngine;
import com.components.scraper.service.core.MpnSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * HTTP-based implementation of {@link MpnSearchService} for Murata Electronics.
 * Uses the Murata <code>/webapi/PsdispRest</code> endpoint to look up
 * manufacturer part numbers (MPNs) and returns a list of product records
 * with their associated specifications.
 * </p>
 *
 * <p><strong>Workflow:</strong></p>
 * <ol>
 *   <li>Determine the Murata category code from the MPN prefix via {@link VendorSearchEngine#cateFromPartNo(String)}</li>
 *   <li>Construct a fully-qualified URI using the vendor’s base URL and MPN search path</li>
 *   <li>Perform an HTTP GET via {@link VendorSearchEngine#safeGet(java.util.function.Function)}</li>
 *   <li>Parse the returned JSON grid into a list of maps using the injected {@link JsonGridParser}</li>
 * </ol>
 *
 * <p>
 * Example request URI:
 * <pre>
 *   <a href="https://www.murata.com/webapi/PsdispRest?cate=luCeramicCapacitorsSMD&amp;partno=GRM0115C1C100&amp;stype=1&amp;lang=en-us">
 *     https://www.murata.com/webapi/PsdispRest
 *   </a>
 * </pre>
 * </p>
 *
 */
@Slf4j
@Service("murataMpnSvc")
public class MurataMpnSearchService
        extends VendorSearchEngine implements MpnSearchService {

    /**
     * Parser that converts the Murata JSON grid into
     * {@code List<Map<String,Object>>}.
     */
    private final JsonGridParser parser;

    /**
     * Constructs the Murata MPN search service.
     *
     * @param parser    JSON-grid parser bean qualified as "murataGridParser"
     * @param factory   factory for loading Murata vendor configuration
     * @param client    configured {@link RestClient} for HTTP calls
     * @param llmHelper LLMHelper to ask ChatGPT for the vendor’s real “cate” code
     */
    public MurataMpnSearchService(
            @Qualifier("murataGridParser") final JsonGridParser parser,
            final VendorConfigFactory factory,
            final RestClient client,
            final LLMHelper llmHelper
    ) {
        super(factory.forVendor("murata"), client, llmHelper);
        this.parser = parser;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Builds and executes a GET request to Murata’s MPN lookup endpoint,
     * then parses the returned JSON grid into a list of specification maps.
     * </p>
     * <p>This implementation will:</p>
     * <ol>
     *   <li>Ask the LLM (via {@link LLMHelper}) for Murata’s true <code>cate</code> code</li>
     *   <li>If the LLM returns a non‐empty list, take its last element as <code>cate</code></li>
     *   <li>Otherwise fall back to {@link #cateFromPartNo(String)}</li>
     *   <li>Call the Murata JSON endpoint and parse the result grid</li>
     * </ol>
     *
     * @param mpn the manufacturer part number (e.g. "GRM0115C1C100GE01")
     * @return a list of maps where each map represents one matching product
     *         record; each key is a human-readable column header, and each
     *         value is the corresponding cell value
     * @throws IllegalStateException if an unexpected HTTP error occurs
     */
    @Override
    public List<Map<String, Object>> searchByMpn(final String mpn) {

        // Clean the MPN string (trim whitespace, leave trailing "#" if present)
        String cleaned = mpn.trim();

        // Discover cate via site‐search
        String cate = discoverCate(cleaned);

        // Fallback to search cate if needed
        if (!StringUtils.hasText(cate)) {
            // Determine the Murata category code based on the MPN prefix
            cate = cateFromPartNo(cleaned);
        }

        final String cateValue = cate;

        // Perform the HTTP GET and parse the JSON grid
        JsonNode root = safeGet(ub -> {
            URI base = URI.create(getCfg().getBaseUrl());   // e.g. https://www.murata.com

            // Apply scheme, host, and port from the base URL
            ub = ub.scheme(base.getScheme())
                    .host(base.getHost());
            if (base.getPort() != -1) {                     // port is -1 when absent
                ub = ub.port(base.getPort());
            }

            // Prepend any existing path segment in the base URL
            String basePath = base.getPath();
            if (StringUtils.hasText(basePath) && !"/".equals(basePath)) {
                ub = ub.path(basePath);                     // e.g. "" or "/"
            }

            // Build the full endpoint URI with query parameters
            return ub.path(getCfg().getMpnSearchPath())     // /webapi/PsdispRest
                    .queryParam("cate",   cateValue)
                    .queryParam("partno", cleaned)
                    .queryParam("stype",  1)
                    .queryParam("lang",   "en-us")
                    .build();
        });

        // Parse the grid into a list of maps and return
        return parser.parse(root).stream().toList();
    }

    /**
     * Call Murata’s public “site search” JSON endpoint to pull out the
     * first category_id from its "categories" array.
     */
    private String discoverCate(String mpn) {
        JsonNode resp = safeGet(getProductSitesearchUri(mpn));
        if(resp == null) {
            log.warn("No response from site-search for Competitor MPN {}", mpn);
            return null;
        }
        JsonNode cats = resp.path("categories");
        if (cats.isArray() && !cats.isEmpty()) {
            JsonNode first = cats.get(0);
            JsonNode children = first.path("children");
            if (children.isArray() && !children.isEmpty()) {
                String childCate = children.get(0).path("category_id").asText(null);
                if (StringUtils.hasText(childCate)) {
                    log.info("Using child category '{}' for MPN {}", childCate, mpn);
                    return childCate;
                }
            }
            // no valid child, fall back to parent
            String parentCate = first.path("category_id").asText(null);
            if (StringUtils.hasText(parentCate)) {
                log.info("Using parent category '{}' for MPN {}", parentCate, mpn);
                return parentCate;
            }
        }

        log.warn("Murata site‐search returned no categories for MPN {}", mpn);
        return null;
    }

}
