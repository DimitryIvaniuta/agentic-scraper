package com.components.scraper.service.kemet;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.MpnSearchService;
import com.components.scraper.service.core.VendorSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
@Service("kemetMpnSvc")
@ConditionalOnProperty(prefix = "scraper.configs.kemet", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KemetMpnSearchService extends VendorSearchEngine
        implements MpnSearchService {

    /** JSON key used by KEMET that contains the part array. */
    private static final String JSON_KEY_PARTS = "detectedUniqueParts";

    /** Default <code>definitionId</code> constant – empirical value. */
    private static final int DEFINITION_ID = 482;

    /**
     * Constructs the Kemet MPN search service.
     *
     * @param parser    JSON-grid parser bean qualified as "kemetGridParser"
     * @param factory   factory for loading Kemet vendor configuration
     * @param builder   configured Webclient builder
     * @param llmHelper LLMHelper to ask ChatGPT for the vendor’s real “cate” code
     * @param om        Object Mapper
     */
    public KemetMpnSearchService(
            // Parser that converts the TDK HTML grid into row maps
            @Qualifier("kemetGridParser") final JsonGridParser parser,
            final VendorConfigFactory factory,
            final WebClient.Builder builder,
            final LLMHelper llmHelper,
            @Qualifier("scraperObjectMapper") final ObjectMapper om
    ) {
        super(factory.forVendor("kemet"), builder, llmHelper, parser, om);
    }

    @Override
    public String vendor() {
        return "KEMET";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Map<String, Object>> searchByMpn(final String mpn) {
        final String cleaned = (mpn == null) ? "" : mpn.trim();
        if (cleaned.isBlank()) {
            return List.of();
        }

//        MultiValueMap<String, String> baseForm = new LinkedMultiValueMap<>();
//        baseForm.add("definitionId", String.valueOf(DEFINITION_ID));
//        baseForm.add("input", cleaned);
        ObjectNode body = getMapper().createObjectNode()
                .put("definitionId", 482)
                .put("input", cleaned);            // full, trimmed MPN
        body.set("parameters", getMapper().createArrayNode());   // must exist, even if empty

        // Build endpoint URI using vendor‑specific config (fallback to constant)
        final URI endpoint = buildUri(
                getCfg().getBaseUrl(),                // e.g. https://www.kemet.com
                getCfg().getMpnSearchPath(),
                null);

        // Execute request – measure timings similar to the TDK implementation
        long t0 = System.nanoTime();
//        final JsonNode rsp = safePost(endpoint, baseForm);
        final JsonNode rsp = postJson(endpoint, body);
        long t1 = System.nanoTime();

        final List<Map<String, Object>> rows = getParser().parse(rsp);
        long t2 = System.nanoTime();

        log.info("KEMET NET={}ms  PARSE={}ms  TOTAL={}ms",
                (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t2 - t0) / 1_000_000);
        return rows;
    }

}
