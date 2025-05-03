package com.components.scraper.service;

import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.service.core.LlmHelper;
import com.components.scraper.service.core.VendorHttpSearchEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * <h2>MurataHttpMpnSearchService</h2>
 * <p>
 * Light‑weight alternative to the Selenium based scraper.<br>
 * Works for single‑MPN look‑ups that can be fetched directly via
 * the product‑detail page.
 * </p>
 */
@Slf4j
@Service
public class MurataHttpMpnSearchService extends VendorHttpSearchEngine {

//    private static final String DETAIL_URL =
//            "https://www.murata.com/en-us/products/productdetail?partno=%s&stype=2&scon=%s";

//    private static final String DEFAULT_CATE = "luCeramicCapacitorsSMD";


    public MurataHttpMpnSearchService(
                                  LlmHelper llmHelper,
                                  VendorConfigFactory factory,
                                  @Qualifier("scraperObjectMapper") ObjectMapper mapper,
                                  WebClient.Builder builder
                                  ) {
        super(llmHelper, factory.forVendor("murata"), mapper, builder);
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Fetches Murata product information by MPN (and optional category).
     *
     * @param mpn      exact Murata part number, e.g. <code>GRM0115C1C100GE01</code>
     * @return Map with keys: mpn, url, specifications (sub‑map)
     */
    @Override
    public List<Map<String, Object>> searchByMpn(String mpn) {
        String cleaned = mpn.replace("#", "").trim();

//        String cate = getCfg().getCategories()
//                .orElse(getCfg().getDefaultCate());
        String cate = cateFromPartNo(mpn);
                // -----------------------------------------------------------------
        // 1) call Murata HTTP endpoint
        // -----------------------------------------------------------------
        log.debug("Murata HTTP search [{}] with cate={}", cleaned, cate);

        String body = getClient().get()
                .uri(builder -> buildUri(builder, cate, cleaned))   // ← lambda
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));
        if (StringUtils.isBlank(body)) {
            log.warn("Empty response for {}", cleaned);
            return Collections.emptyList();
        }

        // -----------------------------------------------------------------
        // 2) choose parser by first non‑blank character
        // -----------------------------------------------------------------
        try {
            return body.trim().startsWith("{") || body.trim().startsWith("[")
                    ? parseJson(body)
                    : parseHtml(body);
        } catch (Exception ex) {
            log.error("Parsing failed for Murata response (mpn={})", cleaned, ex);
            return Collections.emptyList();
        }
    }

/*
    @Override
    public List<Map<String, Object>> searchByMpn(String mpn) {

        // derive cate (category code) from the prefix, or fall back
        String cate = getLlm().cateFromPrefix(mpn).orElse(DEFAULT_CATE);

//        String encoded = URLEncoder.encode(mpn + "#", StandardCharsets.UTF_8);
        String url = getBaseUrl().formatted(mpn, getLlm().cateFromPrefix(mpn));

        String html = fetch(url);
        Document doc = Jsoup.parse(html, url);

        */
/* ---------- try the structured data block first ------------------ *//*

        Optional<Map<String, Object>> fromJsonLd = extractJsonLd(doc);
        if (fromJsonLd.isPresent()) {
            return fromJsonLd.get();
        }

        */
/* ---------- fallback: scrape the visible specification grid ------ *//*

        return extractFromHtmlTable(doc, mpn, url);
    }
*/

    /* ==================================================================== */
    /*  HTTP & parsing helpers                                              */
    /* ==================================================================== */

    private java.net.URI buildUri(UriBuilder ub, Object... args) {
        return ub.path(getCfg().getMpnSearchPath())
                .queryParam("cate", args[0])
                .queryParam("partno", args[1])
                .queryParam("stype", "1")
                .queryParam("lang", "en-us")
                .build();
    }

    private String fetch(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                    "Chrome/135 Safari/537.36")
                    .timeout(Duration.ofSeconds(20))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw new IllegalStateException("Murata returned HTTP " + res.statusCode());
            }
            return res.body();
        } catch (Exception e) {
            throw new IllegalStateException("HTTP fetch failed: " + e.getMessage(), e);
        }
    }

    /** Reads the first JSON‑LD block whose @type == Product */
    private Optional<Map<String, Object>> extractJsonLd(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode tree = mapper.readTree(script.data());
                if (tree.has("@type") && "Product".equals(tree.get("@type").asText())) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("mpn", tree.path("mpn").asText());
                    out.put("url", tree.path("url").asText());
                    out.put("name", tree.path("name").asText());
                    out.put("description", tree.path("description").asText());
                    if (tree.has("additionalProperty")) {
                        Map<String, Object> spec = new LinkedHashMap<>();
                        for (JsonNode p : tree.get("additionalProperty")) {
                            spec.put(p.path("name").asText(),
                                    p.path("value").asText());
                        }
                        out.put("specifications", spec);
                    }
                    return Optional.of(out);
                }
            } catch (Exception ignore) {
            }
        }
        return Optional.empty();
    }

    /** Scrapes the big Angular “main‑table” grid as last resort */
    private Map<String, Object> extractFromHtmlTable(Document doc,
                                                     String mpn,
                                                     String url) {

        Element table = doc.selectFirst("table.main-table");
        if (table == null) {
            throw new NoSuchElementException("Specification table not found");
        }

        /* headers are duplicated – use the *visible* header row-0 only */
        Elements headerCells = table.select("tr.row-header-1 th");
        List<String> headers = headerCells.stream()
                .map(e -> e.text().trim())
                .toList();

        /* first data row (row‑0) contains the requested MPN */
        Element dataRow = table.selectFirst("tr.row-0");
        Elements tds = dataRow.select("td");

        Map<String, String> specs = new LinkedHashMap<>();
        for (int i = 0; i < headers.size() && i < tds.size(); i++) {
            String key = headers.get(i);
            String val = tds.get(i).text().trim();
            if (!key.isBlank() && !val.isBlank()) {
                specs.put(key, val);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mpn", mpn);
        out.put("url", url);
        out.put("specifications", specs);
        return out;
    }

    private List<Map<String, Object>> parseResultsTable(Document doc) {

        Element table = doc.selectFirst("table.main-table");     // first visible table
        if (table == null) {
            log.warn("No result table found on page");
            return List.of();
        }

        /* -------- header mapping: CSS class -> readable header text ---- */
        Map<String, String> class2Header = new HashMap<>();
        for (Element th : table.select("th.text-header")) {
            String cssCol = th.classNames().stream()
                    .filter(c -> c.startsWith("col_") || c.startsWith("col-"))
                    .findFirst().orElse(null);
            if (cssCol != null) class2Header.put(cssCol, th.text().trim());
        }

        /* -------- iterate each data row -------------------------------- */
        List<Map<String, Object>> out = new ArrayList<>();

        for (Element tr : table.select("tr[class^=row-]")) {
            Element link = tr.selectFirst("td.col-partnumber a[href]");
            if (link == null) continue;

            String mpn     = link.text().replace("#", "").trim();
            String partUrl = link.absUrl("href").replace("%23", "");

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("mpn", mpn);
            entry.put("url", partUrl);

            Map<String, String> specs = new LinkedHashMap<>();

            for (Element td : tr.select("td")) {
                String cssCol = td.classNames().stream()
                        .filter(c -> c.startsWith("col_") || c.startsWith("col-"))
                        .findFirst().orElse(null);

                if (cssCol == null || cssCol.equals("col-partnumber")) continue;

                String value = td.text().trim();
                if (value.isBlank()) continue;

                String key = class2Header.getOrDefault(cssCol, cssCol);
                specs.put(key, value);
            }

            if (!specs.isEmpty()) entry.put("specifications", specs);
            out.add(entry);
        }

        log.debug("Parsed {} Murata rows", out.size());
        return out;
    }

}
