package com.components.scraper.service.tdk;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.MpnSearchService;
import com.components.scraper.service.core.VendorSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.netty.util.CharsetUtil.UTF_8;

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
@ConditionalOnProperty(prefix = "scraper.configs.tdk", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TdkMpnSearchService extends VendorSearchEngine implements MpnSearchService {

    /**
     * Cache of compiled {@link Pattern}s – avoids recompilation overhead.
     */
    private static final Map<String, Pattern> PATTERN_CACHE =
            new ConcurrentHashMap<>();

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
     * Discovered value of <em>site</em> parsed from the warm-up page.
     */
    private final AtomicReference<String> site = new AtomicReference<>(DEFAULT_SITE);
    /**
     * Discovered value of <em>group</em> parsed from the warm-up page.
     */
    private final AtomicReference<String> group = new AtomicReference<>(DEFAULT_GROUP);
    /**
     * Discovered value of <em>design</em> parsed from the warm-up page.
     */
    private final AtomicReference<String> design = new AtomicReference<>(DEFAULT_DESIGN);
    /**
     * Warm-up tuning: Limit.
     */
    private static final int CHUNK_LIMIT = 2_048;          // 2 KB
    /**
     * Warm-up tuning: timeout.
     */
    private static final long WARMUP_TIMEOUT_S = 30;             // seconds

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

        JsonNode rsp = safePost(endpoint, baseForm);

        List<Map<String, Object>> rows = getParser().parse(rsp);

        return rows;
    }

    /**
     * <p>Initiates the warm-up sequence immediately after Spring instantiates
     * the bean. The sequence:</p>
     * <ol>
     *   <li>Sends a <code>GET /en/search/list</code> to obtain the TDK session
     *       cookie and to read the inline JavaScript that contains the
     *       <em>site / group / design</em> constants.</li>
     *   <li>Parses those constants and stores them in the {@link
     *       #site}/{@link #group}/{@link #design} atomic references.</li>
     *   <li>Caches the Mono so the HTTP call is executed exactly once.</li>
     * </ol>
     * <p>The method triggers the Mono asynchronously and also blocks once to
     * make sure warm-up completes during application start-up.</p>
     */
    @PostConstruct
    private void initWarmup() {

        URI entry = buildUri(
                getCfg().getBaseUrl(),          // https://product.tdk.com
                "/en/search/list",              // entry-page that sets cookie
                null);

        Mono<Void> warmUpMono = getWebClient().get()
                .uri(entry)
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                // Read only the first ~2 KB until constants appear
                .bodyToFlux(DataBuffer.class)       // stream
//                .doOnSubscribe(s -> log.info("TDK warm-up: GET {}", entry))
                .map(buf -> buf.toString(UTF_8))
                .scan(new StringBuilder(),
                        (acc, chunk) -> acc.append(chunk.length() > CHUNK_LIMIT ? chunk.substring(0, CHUNK_LIMIT) : chunk))
                .filter(sb -> sb.indexOf("site:") > 0
                        && sb.indexOf("group:") > 0
                        && sb.indexOf("design:") > 0)
                .next()                             // stop at first matching buffer
                .map(StringBuilder::toString)
                .timeout(Duration.ofSeconds(WARMUP_TIMEOUT_S))
                .doOnNext(this::extractConstants)
//                .doOnSuccess(v -> log.info("TDK warm-up finished"))
//                .doOnError(e -> log.warn("TDK warm-up failed: {}", e.toString()))
                .onErrorResume(e -> Mono.empty())   // keep pipeline alive
                .then()
                .cache();

        // Kick it off asynchronously AND wait for completion once
        // No blocking of Spring context start-up
        warmUpMono.subscribe();
        // 1st thread triggers the warm-up; others race but all get same Mono
        warmUpMono.block();

    }

    /**
     * Pull site / group / design out of the entry-page <script> tag.
     * Parses the entry page’s inline {@code <script>} to discover the current
     * <em>site / group / design</em> values required by TDK’s POST endpoint.
     *
     * @param html raw HTML of <code>/en/search/list</code>
     */
    private void extractConstants(final String html) {

        if (html == null || html.isBlank()) {
            log.warn("TDK warm-up: empty HTML");
            return;
        }

        Document doc = Jsoup.parse(html);
        Element scriptEl = doc.selectFirst("script:contains(site)");

        if (scriptEl == null) {
            log.warn("TDK warm-up: <script> with constants not found");
            return;
        }

        String body = scriptEl.html();

        site.set(matchOrDefault(body, "site\\s*:\\s*\"([^\"]+)\"", DEFAULT_SITE));
        group.set(matchOrDefault(body, "group\\s*:\\s*\"([^\"]+)\"", DEFAULT_GROUP));
        design.set(matchOrDefault(body, "design\\s*:\\s*\"([^\"]+)\"", DEFAULT_DESIGN));

        log.info("TDK constants discovered → site={} group={} design={}",
                site.get(), group.get(), design.get());
    }

    /**
     * Regex helper: extract first capture group or default.
     * Returns the first capture group of {@code regex} if it matches
     * {@code src}, otherwise {@code defaultVal}. Patterns are cached for
     * performance.
     *
     * @param src        source text to match (can be {@code null})
     * @param regex      the pattern with <em>one</em> capture group
     * @param defaultVal value to return when no match is found
     * @return extracted string or the provided default
     */
    private String matchOrDefault(final String src,
                                  final String regex,
                                  final String defaultVal) {
        // compile once per pattern & cache in the map
        Pattern p = PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);

        Matcher m = p.matcher(src == null ? "" : src);
        return m.find() ? m.group(1) : defaultVal;
    }

}
