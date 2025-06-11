package com.components.scraper.service.tdk;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.MpnSearchService;
import com.components.scraper.service.core.VendorSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h2>TDK – Manufacturer Part-Number (MPN) Search Service</h2>
 *
 * <p>Scrapes the public JSON API at
 * <code>https://product.tdk.com/pdc_api/en/search/list/search_result</code>
 * and converts the HTML-grid fragment into a list of
 * {@code Map&lt;String,Object&gt;} rows.</p>
 *
 * <p>Highlights:</p>
 * <ul>
 *   <li>Automatic warm-up: issues one <code>GET /en/search/list</code> per JVM
 *       to discover the <em>site / group / design</em> constants and to obtain
 *       the session cookie.</li>
 *   <li>Connection-pooled, gzip-enabled {@link WebClient} inherited from
 *       {@link VendorSearchEngine}.</li>
 *   <li>Thread-safe caching of vendor constants via {@link AtomicReference}s.</li>
 * </ul>
 */
@Slf4j
@Service("tdkMpnSvc")
@ConditionalOnProperty(prefix = "scraper.configs.tdk", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TdkMpnSearchService extends TdkSearchEngine
        implements MpnSearchService {

    /**
     * Fallback <code>site</code> constant used before warm-up completes.
     */
    private static final String DEFAULT_SITE = "FBNXDO0R";

    /**
     * Fallback <code>group</code> constant used before warm-up completes.
     */
    private static final String DEFAULT_GROUP = "tdk_pdc_en";

    /**
     * Fallback <code>design</code> constant used before warm-up completes.
     */
    private static final String DEFAULT_DESIGN = "producttdkcom-en";

    /**
     * Constructs the Murata MPN search service.
     *
     * @param parser    JSON-grid parser bean qualified as "murataGridParser"
     * @param factory   factory for loading Murata vendor configuration
     * @param builder   configured Webclient builder
     * @param llmHelper LLMHelper to ask ChatGPT for the vendor’s real “cate” code
     * @param om        Object Mapper
     */
    public TdkMpnSearchService(
            // Parser that converts the TDK HTML grid into row maps
            @Qualifier("tdkGridParser") final JsonGridParser parser,
            final VendorConfigFactory factory,
            final WebClient.Builder builder,
            final LLMHelper llmHelper,
            @Qualifier("scraperObjectMapper") final ObjectMapper om
    ) {
        super(factory.forVendor("tdk"), builder, llmHelper, parser, om);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String vendor() {
        return "TDK";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Map<String, Object>> searchByMpn(final String mpn) {
        String cleaned = mpn.trim();

        // Build POST form. Prepare constant form fields once (site, group, design, etc.)
        MultiValueMap<String, String> baseForm = new LinkedMultiValueMap<>();
        baseForm.add("site", DEFAULT_SITE);
        baseForm.add("charset", "UTF-8");
        baseForm.add("group", DEFAULT_GROUP);
        baseForm.add("design", DEFAULT_DESIGN);
        baseForm.add("fromsyncsearch", "1");
        baseForm.add("_l", String.valueOf(getCfg().getPageSize()));
        baseForm.add("_c", "part_no-part_no");
        baseForm.add("_d", "0");
        baseForm.add("_p", "1");
        baseForm.add("pn", cleaned);

        // Call API
        URI endpoint = buildUri(getCfg().getBaseUrl(), getCfg().getMpnSearchPath(), null);

        baseForm.set("pn", cleaned);
        long t0 = System.nanoTime();
        JsonNode rsp = safePost(endpoint, baseForm);
        long t1 = System.nanoTime();
        List<Map<String, Object>> rows = getParser().parse(rsp);
        long t2 = System.nanoTime();

        log.info("TDK  NET={} ms  PARSE={} ms  TOTAL={} ms",
                (t1 - t0) / MILLISECONDS_VALUE, (t2 - t1) / MILLISECONDS_VALUE, (t2 - t0) / MILLISECONDS_VALUE);
        return rows;
    }

}
