package com.components.scraper.service.core;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.VendorCfg;
import com.components.scraper.parser.JsonGridParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.logging.LogLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;


import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.util.function.Tuple2;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>VendorSearchEngine</h2>
 *
 * <p>Base class for all <em>HTTP‑only</em> scraper services.<br>
 * Responsibilities:</p>
 * <ul>
 *   <li>Holds immutable {@link VendorCfg} and {@link org.springframework.web.client.RestClient}</li>
 *   <li>Utility wrappers around <strong>GET</strong> with basic timeout /
 *       error handling so subclasses can focus on endpoint‑specific logic.</li>
 *   <li>Convenience helpers for Murata/TDK “cate” mapping from part‑number
 *       prefixes (kept generic enough for any vendor).</li>
 * </ul>
 *
 * <p>The class is stateless and therefore <b>thread‑safe</b>.</p>
 */
@Slf4j
@Getter
//@RequiredArgsConstructor
public abstract class VendorSearchEngine {

    /**
     * Anti‑bot cookies harvested during warm‑up and replayed on each call.
     */
    private final Map<String, String> antiBotCookies = new ConcurrentHashMap<>();

    /**
     * Number of characters to use when extracting a part number prefix.
     */
    public static final int PARTNO_PREFIX_LENGTH = 3;

    /**
     * Holds vendor-specific configuration parameters such as base URL,
     * search paths, and category mappings.
     */
    private final VendorCfg cfg;

    /**
     * Pre‑configured synchronous WebClient instance for executing HTTP requests..
     */
    private final WebClient webClient;

    /**
     * LLMHelper to ask ChatGPT for the vendor’s real “cate” code.
     */
    protected final LLMHelper llmHelper;

    /**
     * Parser that converts a JSON grid section into a list of field→value maps.
     * The injected JSON‐grid parser that transforms a Murata JSON payload
     * into a list of row‐maps.
     */
    private final JsonGridParser parser;

    /**
     * Maximum time to wait for a single TDK/Murata HTTP call to complete.
     */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(25);

    /**
     * TCP connect timeout for the underlying Reactor‐Netty client.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    /**
     * Max time to wait for the first byte of the HTTP response.
     */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);

    /**
     * Upper bound to sit in the connection-pool acquire queue.
     */
    private static final Duration POOL_ACQUIRE_DELAY = Duration.ofSeconds(2);

    /**
     * Max pooled connections shared across all vendor calls.
     */
    private static final int MAX_CONNECTIONS = 50;

    /**
     * Shared Jackson {@link ObjectMapper} – used by {@link #safeGet} / {@link #safePost}.
     */
    private final ObjectMapper mapper;

    protected VendorSearchEngine(final VendorCfg cfg,
                                 final WebClient.Builder builder,
                                 final LLMHelper llmHelper,
                                 final JsonGridParser parser,
                                 final ObjectMapper mapper
    ) {
        this.cfg = cfg;
        this.webClient = buildWebClient(builder);
        this.llmHelper = llmHelper;
        this.parser = parser;
        this.mapper = mapper;
    }

    /**
     * Executes a <strong>synchronous</strong> HTTP&nbsp;<code>GET</code> request
     * against the supplied {@link URI} via the shared, connection-pooled
     * {@link WebClient}.
     *
     * <p>Operational characteristics:</p>
     * <ul>
     *   <li>{@code Accept: application/json} is sent automatically.</li>
     *   <li>The call blocks the calling thread up to
     *       {@link #HTTP_TIMEOUT the configured timeout} and then returns the
     *       decoded {@link JsonNode}.</li>
     *   <li>Any I/O, decoding, or timeout error is logged at <em>warn</em> level
     *       and the method falls back to an <em>empty</em>
     *       <code>{}</code>&nbsp;node produced by the shared
     *       {@link #mapper}—never {@code null}.</li>
     * </ul>
     *
     * @param uri the absolute {@link URI} to request (must not be {@code null})
     * @return the response body mapped to a {@link JsonNode}; never {@code null},
     * guaranteed to be an <em>empty</em> object node on failure
     */
    protected JsonNode safeGet(final URI uri) {
        try {
            return webClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .cookies(c -> antiBotCookies.forEach(c::add))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(HTTP_TIMEOUT)
                    .block();
        } catch (Exception ex) {
            log.warn("safeGet failed for {}: {}", uri, ex.toString());
            return mapper.createObjectNode();
        }
    }

    /**
     * Executes a <strong>blocking</strong> HTTP&nbsp;<code>POST</code> request with
     * an <code>application/x-www-form-urlencoded</code> body and maps the JSON
     * response into a {@link JsonNode}.
     *
     * <h4>Pipeline overview</h4>
     * <ol>
     *   <li>Posts the supplied {@code form} to {@code uri}.</li>
     *   <li>Retrieves the raw response as {@code byte[]} to avoid premature
     *       decoding on the I/O thread.</li>
     *   <li>Shifts JSON parsing to the bounded-elastic scheduler
     *       (<em>CPU-bound work-pool</em>).</li>
     *   <li>Wraps the result with Reactor’s {@link reactor.util.function.Tuple2
     *       Tuple2}&nbsp;via {@link reactor.core.publisher.Mono#elapsed()
     *       Mono.elapsed()} to capture the round-trip time, then immediately
     *       unwraps it.</li>
     *   <li>Applies a global {@link #HTTP_TIMEOUT}. If the timeout or any other
     *       exception occurs, logs a warning and returns an empty object node
     *       instead of propagating the error.</li>
     *   <li>Terminal {@link Mono#block()} produces a concrete {@link JsonNode}
     *       for imperative callers.</li>
     * </ol>
     *
     * <p>Because the method always falls back to
     * {@code mapper.createObjectNode()}, the caller never observes {@code null}
     * and can safely iterate over the JSON structure.</p>
     *
     * @param uri  absolute endpoint URI (scheme + host + path); must not be {@code null}
     * @param form form-fields to be URL-encoded and sent in the POST body;
     *             must not be {@code null} but may be empty
     * @return parsed JSON payload, or an <em>empty</em> object node when the
     * request fails or times out; never {@code null}
     */
    protected JsonNode safePost(final URI uri, final MultiValueMap<String, String> form) {
        try {
            return webClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    /* -------------- raw bytes stage ---------------- */
                    .bodyToMono(byte[].class)
//                .doOnSubscribe(s -> log.debug("TDK call started {}", form))
//                .doOnNext(buf -> log.debug("TDK payload {} bytes", buf.length))
                    /* -------------- JSON mapping stage ------------------ */
                    .publishOn(Schedulers.boundedElastic())
                    .map(this::toJson)
                    /* -------------- timing stage ---------------- */
                    .elapsed()                                           // Mono<Tuple2<Long,JsonNode>>
//                .doOnNext(tp -> log.info("TDK round-trip {} ms", tp.getT1() / 1_000_000))
                    .map(Tuple2::getT2)                               // back to Mono<JsonNode>
                    .timeout(HTTP_TIMEOUT)
                    // graceful degradation
                    .onErrorResume(e -> {
                        log.warn("TDK {} failed: {}", uri.getPath(), e.toString());
                        return Mono.just(mapper.createObjectNode());
                    })
                    .block();
        } catch (Exception ex) {            // protects .block() interruption etc.
            log.warn("safePost failed for {}: {}", uri, ex.toString());
            return mapper.createObjectNode();
        }
    }

    private JsonNode toJson(final byte[] bytes) {
        try {
            return mapper.readTree(bytes);
        } catch (IOException ex) {
            log.warn("JSON parse error: {}", ex.getMessage());
            return mapper.createObjectNode();
        }
    }

    private WebClient buildWebClient(final WebClient.Builder builder) {
        // Connection pooling
        ConnectionProvider pool = ConnectionProvider.builder("vendor-pool")
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireTimeout(POOL_ACQUIRE_DELAY)
                .build();

        // Build the Reactor-Netty HttpClient
        HttpClient httpClient = HttpClient.create(pool)
                .compress(true)
                .protocol(HttpProtocol.H2, HttpProtocol.HTTP11) // HTTP/2 with HTTP/1.1 fallback
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())

                .responseTimeout(RESPONSE_TIMEOUT)
                .wiretap("reactor.netty.http.client.HttpClient",
                        LogLevel.DEBUG,
                        AdvancedByteBufFormat.TEXTUAL)
                .compress(true);

        // Eagerly initialize the pipeline (DNS, SSL, codecs, HTTP/2 ALPN, etc.)
        httpClient.warmup().block();

        // Build WebClient
        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filters(f -> f.add(saveCookies()))               // ⬅️ capture cookies
                .defaultHeaders(h -> {
                    h.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
                    h.set(HttpHeaders.ACCEPT_ENCODING, "br, gzip, deflate");
                    h.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.5");
                    h.set("X-Requested-With", "XMLHttpRequest");
                    h.set(HttpHeaders.USER_AGENT,
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                    + "Chrome/125.0.0.0 Safari/537.36");
                    h.set("Sec-CH-UA", "\"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"");
                    h.set("Sec-CH-UA-Mobile", "?0");
                    h.set("Sec-CH-UA-Platform", "\"Windows\"");
                    h.set(HttpHeaders.REFERER, getCfg().getBaseUrl() + "/en/search/list");
                })
                /* one “Cookie:” header that replays whatever is in the jar               */
                .defaultCookie("dummy", "dummy")                  // placeholder key
                .filter((req, next) -> {
                    ClientRequest mutated = ClientRequest.from(req)
                            .cookies(c -> {
                                c.clear();
                                antiBotCookies.forEach(c::add);
                            })
                            .build();
                    return next.exchange(mutated);
                })
                .build();
    }

    /**
     * Creates an {@link ExchangeFilterFunction} that intercepts every HTTP response
     * and stores all cookies set by the server into the {@code antiBotCookies} map.
     *
     * <p>This filter is intended to capture Akamai anti-bot cookies (e.g.
     * <code>bm_sz</code>, <code>bm_sv</code>, <code>abck</code>, etc.) as soon
     * as they are issued on any response.  Subsequent requests will replay these
     * cookies to avoid bot-throttling delays.</p>
     *
     * @return an {@link ExchangeFilterFunction} which, for each client response,
     *         iterates over all {@link org.springframework.http.ResponseCookie ResponseCookie}
     *         instances, stores each cookie name and value into the
     *         {@code antiBotCookies} map, and then returns the original response
     *         unchanged
     */
    private ExchangeFilterFunction saveCookies() {
        return ExchangeFilterFunction.ofResponseProcessor(resp -> {
            resp.cookies().values().stream()
                    .flatMap(Collection::stream)
                    .forEach(c -> antiBotCookies.put(c.getName(), c.getValue()));
            return Mono.just(resp);
        });
    }

    /**
     * Anti-bot cookie propagation.
     * Store a single <code>bm_*</code> Akamai anti-bot cookie so that every
     * subsequent call made through {@link #webClient} automatically replays it.
     * <p>
     * The method is invoked from a vendor’s warm-up phase (e.g. TDK) each time
     * the entry page sets or refreshes a cookie.  Values are kept in a
     * thread-safe {@link ConcurrentHashMap}; the most recent value wins.
     * <br>
     * Subclasses should never call the map directly—always use this method so
     * future logic (e.g. expiry handling) is centralised.
     *
     * @param name  cookie name (usually starts with <code>bm_</code>);
     *              must not be {@code null}
     * @param value cookie value; may be empty but never {@code null}
     */
    protected void putAntiBotCookie(@NonNull final String name,
                                    @NonNull final String value) {

        String previous = antiBotCookies.put(name, value);
        if (!value.equals(previous)) {
            log.debug("Anti-bot cookie updated: {}={}", name, value);
        }
    }

    /**
     * Constructs a {@link URI} from the given base URL, optional path, and
     * optional query parameters map.
     * <p>
     * Preserves any existing encoding in the base or path segments.
     * </p>
     *
     * @param base the base URL, e.g. {@code https://api.vendor.com}
     * @param path optional path relative to {@code base}, may be null or blank
     * @param q    optional multi-value query parameters, may be null or empty
     * @return fully built and encoded {@link URI}
     */
    protected URI buildUri(@NonNull final String base,
                           @Nullable final String path,
                           @Nullable final MultiValueMap<String, String> q) {

        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(base);

        if (StringUtils.isNotBlank(path)) {
            b.path(path.startsWith("/") ? path : "/" + path);
        }

        if (q != null && !q.isEmpty()) {
            b.queryParams(q);
        }
        return b.build(false).toUri();   // keep already‑encoded ‘|’ etc.
    }

    /**
     * Determines the vendor category code for a given part number by its
     * three-character prefix. Falls back to the vendor’s default category
     * if no mapping exists.
     *
     * @param partNo the full manufacturer part number (may be null/blank)
     * @return the corresponding category code
     * @throws NoSuchElementException if no prefix mapping or default is configured
     */
    public String cateFromPartNo(@Nullable final String partNo) {

        if (StringUtils.isBlank(partNo) || partNo.length() < PARTNO_PREFIX_LENGTH) {
            return defaultCateOrFail();
        }

        String prefix = partNo.substring(0, PARTNO_PREFIX_LENGTH).toUpperCase(Locale.ROOT);
        String cate = getCfg().getCategories().get(prefix);

        return cate != null ? cate : defaultCateOrFail();
    }

    /**
     * Builds the URI for Murata’s public “site search” endpoint to discover
     * product categories by MPN.  The resulting URI will include the following
     * query parameters:
     * <ul>
     *   <li><code>op=AND</code> — logical AND operator</li>
     *   <li><code>q={mpn}</code> — the part number to query</li>
     *   <li><code>src=product</code> — restrict search to products</li>
     *   <li><code>region=en-us</code> — specify the English (US) site region</li>
     * </ul>
     *
     * @param mpn the manufacturer part number to search for (must not be blank)
     * @return a {@link URI} pointing to the Murata site‐search API for the given MPN
     */
    protected URI getProductSitesearchUri(final String mpn) {
        MultiValueMap<String, String> q = new LinkedMultiValueMap<>();
        q.add("op", "AND");
        q.add("q", mpn);
        q.add("src", "product");
        q.add("region", "en-us");

        return buildUri(
                getCfg().getBaseUrlSitesearch(),
                getCfg().getMpnSearchProduct(),
                q
        );
    }

    /**
     * Determines the vendor category code for a cross-reference lookup by
     * sanitizing the competitor part number, extracting its prefix, and
     * mapping via {@link VendorCfg#getCrossRefCategories()}.
     *
     * @param competitorPn competitor part number string
     * @return mapped cross-reference category code, or the configured default
     */
    public String cateForCrossRef(final String competitorPn) {

        String sanitized = competitorPn.replaceAll("[^A-Z0-9]", "");
        String prefix = sanitized.length() >= PARTNO_PREFIX_LENGTH
                ? sanitized.substring(0, PARTNO_PREFIX_LENGTH).toUpperCase(Locale.ROOT)
                : sanitized.toUpperCase(Locale.ROOT);

        Map<String, String> xref = getCfg().getCrossRefCategories();
        String cate = (xref != null) ? xref.get(prefix) : null;

        return (cate != null ? cate : getCfg().getCrossRefDefaultCate());
    }

    /**
     * Returns the configured default category code or raises an exception
     * if none is provided.
     *
     * @return default category value.
     */
    private String defaultCateOrFail() {
        List<String> list = List.of("x", "y", "z");
        String[] arr = list.toArray(value -> new String[value]);

        String def = getCfg().getDefaultCate();
        if (def != null) {
            return def;
        }

        throw new NoSuchElementException("No cate mapping/default for vendor "
                + getCfg().getBaseUrl());
    }
}
