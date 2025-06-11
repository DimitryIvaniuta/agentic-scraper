package com.components.scraper.service.murata;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.VendorSearchEngine;
import com.components.scraper.service.core.MpnSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
@Service("murataMpnSvc")
public class MurataMpnSearchService
        extends VendorSearchEngine implements MpnSearchService {

    /**
     * Constructs the Murata MPN search service.
     *
     * @param parser    JSON-grid parser bean qualified as "murataGridParser"
     * @param factory   factory for loading Murata vendor configuration
     * @param builder   configured Builder for HTTP calls
     * @param llmHelper LLMHelper to ask ChatGPT for the vendor’s real “cate” code
     * @param om        Object Mapper
     */
    public MurataMpnSearchService(
            @Qualifier("murataGridParser") final JsonGridParser parser,
            final VendorConfigFactory factory,
            final WebClient.Builder builder,
            final LLMHelper llmHelper,
            @Qualifier("scraperObjectMapper") final ObjectMapper om
    ) {
        super(factory.forVendor("murata"), builder, llmHelper, parser, om);
    }

    @Override
    public String vendor() {
        return "MURATA";
    }

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

        MultiValueMap<String, String> q = new LinkedMultiValueMap<>();
        q.add("cate", cateValue);          // discovered earlier
        q.add("partno", cleaned);            // cleaned = mpn.trim()
        q.add("stype", "1");
        q.add("lang", "en-us");

        URI uri = buildUri(
                getCfg().getBaseUrl(),       // e.g. https://www.murata.com
                getCfg().getMpnSearchPath(), // e.g. /webapi/PsdispRest
                q);

        JsonNode root = safeGet(uri);        // pooled, gzip WebClient

        // Parse the grid into a list of maps and return
        return getParser().parse(root).stream().toList();
    }

    private String discoverCate(final String mpn) {
        if (!StringUtils.hasText(mpn) || mpn.length() < PARTNO_PREFIX_LENGTH) {
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
