package com.components.scraper.service.core;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.VendorCfg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

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
     * Pre‑configured synchronous RestClient instance for executing HTTP requests..
     */
    protected final org.springframework.web.client.RestClient client;

    /**
     * LLMHelper to ask ChatGPT for the vendor’s real “cate” code.
     */
    protected final LLMHelper llmHelper;

    /**
     * Performs an HTTP GET to the URI produced by {@code uriFn}, returning
     * a {@link JsonNode} body, or an empty node/​null if safe handling
     * of HTTP errors is needed.
     *
     * @param uriFn function that builds the request URI from a {@link UriBuilder}
     * @return parsed {@link JsonNode} response, or {@code null} for 404s,
     * or empty object node for other I/O errors
     * @throws IllegalStateException for 5xx error codes (unless 4xx client error)
     */
    protected @Nullable JsonNode safeGet(@NonNull final Function<UriBuilder, URI> uriFn) {
        try {
            return client.get()
                    .uri(uriFn)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpServerErrorException ex) {
            log.warn("Upstream {} returned {}: {} → returning empty JSON",
                    cfg.getBaseUrl(),
                    ex.getStatusCode(),
                    ex.getResponseBodyAsString()
            );
        } catch (Exception ex) {
            // any other I/O or mapping errors
            log.error("safeGet failed for {} → returning empty JSON",
                    uriFn, ex);
        }
        return JsonNodeFactory.instance.objectNode();
    }

    /**
     * Overload of safeGet(Function) for a prebuilt {@link URI}.
     *
     * @param uri the target endpoint URI
     * @return parsed {@link JsonNode} response, or {@code null}/empty node
     */
    protected JsonNode safeGet(@NonNull final URI uri) {
        return safeGet(ub -> uri);
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

        return b.build(true).toUri();   // keep already‑encoded ‘|’ etc.
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
        String def = getCfg().getDefaultCate();
        if (def != null) {
            return def;
        }

        throw new NoSuchElementException("No cate mapping/default for vendor "
                + getCfg().getBaseUrl());
    }
}
