package com.components.scraper.service.core;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.VendorCfg;
import com.components.scraper.parser.JsonGridParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

@Slf4j
@Getter
public abstract class VendorSearchEngine {

    public static final Long MILLISECONDS_VALUE = 1_000_000L;

    private final Map<String, String> antiBotCookies = new ConcurrentHashMap<>();

    public static final int PARTNO_PREFIX_LENGTH = 3;

    private final VendorCfg cfg;

    private final WebClient webClient;

    protected final LLMHelper llmHelper;

    private final JsonGridParser parser;

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(25);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);

    private static final Duration POOL_ACQUIRE_DELAY = Duration.ofSeconds(2);

    private static final int MAX_CONNECTIONS = 50;

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

    protected JsonNode safePost(final URI uri, final MultiValueMap<String, String> form) {
        try {
            return webClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .publishOn(Schedulers.boundedElastic())
                    .map(this::toJson)
                    .elapsed()                                           // Mono<Tuple2<Long,JsonNode>>
                    .map(Tuple2::getT2)                               // back to Mono<JsonNode>
                    .timeout(HTTP_TIMEOUT)
                    // graceful degradation
                    .onErrorResume(e -> {
                        log.warn("POST Resource {} failed: {}", uri.getPath(), e.toString());
                        return Mono.just(mapper.createObjectNode());
                    })
                    .block();
        } catch (Exception ex) {            // protects .block() interruption etc.
            log.warn("safePost failed for {}: {}", uri, ex.toString());
            return mapper.createObjectNode();
        }
    }

    protected JsonNode postJson(final URI uri, final ObjectNode body) {
        return webClient.post()
                .uri(uri)                    // https://www.kemet.com/en/us/search.products.json
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                // a couple of real-browser headers keeps Akamai/CDN quiet
                .header("Origin", "https://www.kemet.com")
                .header("Referer", "https://www.kemet.com/en/us")
                .header("User-Agent",
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(HTTP_TIMEOUT);
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


    private ExchangeFilterFunction saveCookies() {
        return ExchangeFilterFunction.ofResponseProcessor(resp -> {
            resp.cookies().values().stream()
                    .flatMap(Collection::stream)
                    .forEach(c -> antiBotCookies.put(c.getName(), c.getValue()));
            return Mono.just(resp);
        });
    }


    protected void putAntiBotCookie(@NonNull final String name,
                                    @NonNull final String value) {

        String previous = antiBotCookies.put(name, value);
        if (!value.equals(previous)) {
            log.debug("Anti-bot cookie updated: {}={}", name, value);
        }
    }


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

    public String cateForCrossRef(final String competitorPn) {

        String sanitized = competitorPn.replaceAll("[^A-Z0-9]", "");
        String prefix = sanitized.length() >= PARTNO_PREFIX_LENGTH
                ? sanitized.substring(0, PARTNO_PREFIX_LENGTH).toUpperCase(Locale.ROOT)
                : sanitized.toUpperCase(Locale.ROOT);

        Map<String, String> xref = getCfg().getCrossRefCategories();
        String cate = (xref != null) ? xref.get(prefix) : null;

        return (cate != null ? cate : getCfg().getCrossRefDefaultCate());
    }


    private String defaultCateOrFail() {
        String def = getCfg().getDefaultCate();
        if (def != null) {
            return def;
        }

        throw new NoSuchElementException("No cate mapping/default for vendor "
                + getCfg().getBaseUrl());
    }
}
