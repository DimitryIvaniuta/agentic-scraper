package com.components.scraper.service.core;

import com.components.scraper.config.VendorCfg;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownContentTypeException;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.*;

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
@RequiredArgsConstructor
public abstract class VendorSearchEngine {

    /** Immutable vendor‑level configuration (URLs, mapping tables …). */
    private final VendorCfg cfg;

    /** Pre‑configured synchronous RestClient (WireMock or live). */
    protected final org.springframework.web.client.RestClient client;

    /* ------------------------------------------------------------------ */
    /*  HTTP helpers                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Perform <strong>GET</strong> and decode JSON into {@link JsonNode}.
     *
     * @param uriFn function that receives a Spring {@link UriBuilder}
     *              and returns a fully‑built {@link URI}
     *
     * @return root JSON node (may be {@code null} if 204/404)
     *
     * @throws IllegalStateException if a non‑2xx was received or JSON could
     *                               not be parsed.
     */
    protected @Nullable JsonNode safeGet(@NonNull java.util.function.Function<UriBuilder, URI> uriFn) {
        try {
            return client.get()
                    .uri(uriFn)
                    .retrieve()
                    .body(JsonNode.class);
        }
        catch (HttpStatusCodeException ex) {
            HttpStatusCode s = ex.getStatusCode();
            log.warn("{} GET {} → {}", getCfg().getBaseUrl(),
                    ex.getResponseHeaders() == null ? "" : ex.getResponseHeaders().getLocation(),
                    s);
            if (s.is4xxClientError()) return null;           // 404 … treat as “no data”
            throw new IllegalStateException("HTTP " + s, ex);
        }
        catch (RestClientException ex) {
            throw new IllegalStateException("Cannot fetch/parse remote JSON", ex);
        }
    }

    /**
     * Convenience overload for simple GET with static URI.
     */
    protected JsonNode safeGet(@NonNull URI uri) {
        return safeGet(ub -> uri);
    }

    /**
     * Build a URI from base URL + path + query params (null‑safe).
     */
    protected URI buildUri(@NonNull String base,
                           @Nullable String path,
                           @Nullable MultiValueMap<String, String> q) {

        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(base);

        if (StringUtils.isNotBlank(path))
            b.path(path.startsWith("/") ? path : "/" + path);

        if (q != null && !q.isEmpty())
            b.queryParams(q);

        return b.build(true).toUri();   // keep already‑encoded ‘|’ etc.
    }

    /* ------------------------------------------------------------------ */
    /*  Category helpers                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * Resolve “cate” from a Murata/TDK part number prefix.
     *
     * @throws NoSuchElementException if mapping table does not contain prefix
     *                                <i>and</i> a default was not configured.
     */
    public String cateFromPartNo(@Nullable String partNo) {

        if (StringUtils.isBlank(partNo) || partNo.length() < 3)
            return defaultCateOrFail();

        String prefix = partNo.substring(0, 3).toUpperCase(Locale.ROOT);
        String cate   = getCfg().getCategories().get(prefix);

        return cate != null ? cate : defaultCateOrFail();
    }

    /** Dedicated helper for Murata cross‑reference look‑ups. */
    public String cateForCrossRef(String competitorPn) {

        String sanitized = competitorPn.replaceAll("[^A-Z0-9]", "");
        String prefix    = sanitized.length() >= 3
                ? sanitized.substring(0, 3).toUpperCase(Locale.ROOT)
                : sanitized.toUpperCase(Locale.ROOT);

        Map<String,String> xref = getCfg().getCrossRefCategories();
        String cate = (xref != null) ? xref.get(prefix) : null;

        return (cate != null ? cate : getCfg().getCrossRefDefaultCate());
    }

    /* ------------------------------------------------------------------ */
    /*  internals                                                         */
    /* ------------------------------------------------------------------ */

    private String defaultCateOrFail() {
        String def = getCfg().getDefaultCate();
        if (def != null) return def;

        throw new NoSuchElementException("No cate mapping/default for vendor "
                + getCfg().getBaseUrl());
    }

    /* ------------------------------------------------------------------ */
    /*  timeouts (optional)                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Recommended default (can be referenced by subclasses):
     * <pre>{@code
     * client.get()
     *       .uri(uri)
     *       .retrieve()
     *       .timeout(Duration.ofSeconds(10))
     * }</pre>
     */
    protected static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
}
