package com.components.scraper.service.tdk;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.VendorCfg;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.VendorSearchEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// other imports omitted for brevity

/**
 * Base class for all TDK‐specific scrapers.
 * Handles the one‐time “warm-up” to fetch site/group/design and anti-bot cookies.
 */
@Slf4j
@Getter
public abstract class TdkSearchEngine extends VendorSearchEngine {

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

    // Guard to ensure warm-up only runs once
    private static final AtomicBoolean WARMED = new AtomicBoolean(false);

    // How many chars of HTML to scan for the constants
    private static final int CHUNK_LIMIT = 2_048;

    /**
     * How long to wait for the warm-up stream.
     * Warm-up tuning: timeout.
     */
    private static final long WARMUP_TIMEOUT_S = 30;             // seconds
    protected TdkSearchEngine(VendorCfg cfg,
                              WebClient.Builder builder,
                              LLMHelper llmHelper,
                              JsonGridParser parser,
                              ObjectMapper mapper) {
        super(cfg, builder, llmHelper, parser, mapper);
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

        if (!WARMED.compareAndSet(false, true)) {
            return;  // already done
        }

        URI entry = buildUri(
                getCfg().getBaseUrl(),          // https://product.tdk.com
                "/en/search/list",              // entry-page that sets cookie
                null);

        Mono<Void> warmUpMono = getWebClient().get()
                .uri(entry)
                .accept(MediaType.TEXT_HTML)
                .exchangeToMono(resp -> {
                    // --- capture Akamai bm_* cookies ---
                    resp.cookies().values().stream()
                            .flatMap(List::stream)
                            .filter(c -> c.getName().startsWith("bm_"))
                            .forEach(c -> putAntiBotCookie(c.getName(), c.getValue()));

                    // --- stream small chunk until constants appear ---
                    return resp.bodyToFlux(DataBuffer.class)
                            .map(buf -> buf.toString(StandardCharsets.UTF_8))
                            .scan(new StringBuilder(), (acc, chunk) -> acc.append(
                                    chunk, 0, Math.min(chunk.length(), CHUNK_LIMIT)))
                            .filter(sb -> sb.indexOf("site:") > 0
                                    && sb.indexOf("group:") > 0
                                    && sb.indexOf("design:") > 0)
                            .next()
                            .map(StringBuilder::toString);
                })
                .timeout(Duration.ofSeconds(WARMUP_TIMEOUT_S))
                .doOnNext(this::extractConstants)
                .doOnSuccess(v -> log.info("TDK warm-up finished"))
                .doOnError(e -> log.warn("TDK warm-up failed: {}", e.toString()))
                .onErrorResume(e -> Mono.empty())
                .onErrorResume(e -> {
                    log.warn("TDK warm‑up failed: {}", e.toString());
                    return Mono.empty();
                })
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
