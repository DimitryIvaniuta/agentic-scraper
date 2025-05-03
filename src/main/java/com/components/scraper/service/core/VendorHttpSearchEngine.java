package com.components.scraper.service.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import com.components.scraper.config.VendorCfg;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.components.scraper.config.VendorProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.web.util.UriBuilder;

/**
 * <h2>VendorHttpSearchEngine</h2>
 *
 * <p>Reusable base‑class for scrapers that work with a <strong>single
 * HTTP&nbsp;request</strong> (no Selenium / WebDriver).</p>
 *
 * <ul>
 *   <li>Handles <em>polite</em> HTTP calls (time‑outs, UA string, retry helper).</li>
 *   <li>Provides convenient helpers to download &amp; parse HTML with
 *       <a href="https://jsoup.org/">JSoup</a>.</li>
 *   <li>Leaves the vendor‑specific parsing to concrete subclasses.</li>
 * </ul>
 *
 * <p>Usage:</p>
 *
 * <pre>{@code
 * public class MurataHttpMpnSearchService extends VendorHttpSearchEngine {
 *     public MurataHttpMpnSearchService() {
 *         super("https://www.murata.com");
 *     }
 *
 *     @Override
 *     public List<Map<String, Object>> searchByMpn(String mpn) {
 *         String url = baseUrl() + "/en-us/search/productsearch?keyWords=" +
 *                      urlEncode(mpn);
 *         Document doc = fetchDocument(url);
 *         // …parse…
 *     }
 * }
 * }</pre>
 */
@Slf4j
@Getter
public abstract class VendorHttpSearchEngine {

    private final LlmHelper llm;

    private final VendorCfg cfg;

    private final WebClient                  client;
    private final ObjectMapper               mapper;

    protected VendorHttpSearchEngine(LlmHelper llm, VendorCfg cfg,
                                     @Qualifier("scraperObjectMapper") ObjectMapper mapper,
                                     WebClient.Builder builder) {
        this.llm = llm;
        this.cfg = cfg;
        this.mapper = mapper;
        this.client = builder
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/135.0 Safari/537.36")
                .build();

    }

    /** Shared immutable {@link HttpClient}. */
    private final HttpClient http =
            HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();


    /** The canonical vendor root URL (no trailing slash). */
    protected final String baseUrl() { return cfg.getBaseUrl(); }


    public List<Map<String, Object>> searchByMpn(String mpn) {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + " does not implement MPN search");
    }

    public List<Map<String, Object>> searchByParameters(
            String category, String sub, Map<String, Object> filters, int max) {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + " does not implement parametric search");
    }

    public Map<String, List<Map<String, Object>>> searchByCrossReference(
            String competitorMpn, List<String> categoryPath) {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + " does not implement X-Ref search");
    }

    /* ------------------------------------------------------------------ */
    /* HTTP helpers                                                       */
    /* ------------------------------------------------------------------ */
    public String cateFromPartNo(String partNo) {
        if (StringUtils.isNotBlank(partNo)
                && partNo.length() > 3) {
            String prefix = partNo.substring(0, 3).toUpperCase(Locale.ROOT);
            String categoryResult = getCfg().getCategories().get(prefix);
            return Optional.ofNullable(categoryResult)
                    .orElseThrow(() -> new NoSuchElementException("No Category found for part number"));
        } else {
            return getCfg().getDefaultCate();
        }
    }

    /**   Guess <code>cate</code> for Murata cross‑reference lookup.   */
    public String cateForCrossRef(String pn) {
        String pref = pn.replaceAll("[^A-Z0-9]", "")
                .substring(0, Math.min(3, pn.length()))
                .toUpperCase();

        String    cate  = getCfg().getCrossRefCategories().get(pref);

        return (cate != null ? cate : getCfg().getCrossRefDefaultCate());
    }

    /** URL‑encodes using UTF‑8. */
    protected static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Perform a <strong>single GET</strong>.  Throws an {@link IllegalStateException}
     * for non‑20x responses so callers don’t need to check every time.
     */
    protected String httpGet(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User‑Agent",
                            "Mozilla/5.0 (compatible; ComponentsScraper/1.0)")
                    .GET()
                    .build();

            HttpResponse<String> rsp = http.send(req,
                    HttpResponse.BodyHandlers.ofString());

            int code = rsp.statusCode();
            if (code / 100 != 2) {                        // non‑20x
                throw new IllegalStateException(
                        "HTTP " + code + " for " + url);
            }
            return rsp.body();
        } catch (Exception ex) {
            throw new IllegalStateException("GET " + url + " failed", ex);
        }
    }

    /** Convenience: download and parse HTML in one call. */
    protected Document fetchDocument(String url) {
        String html = httpGet(url);
        return Jsoup.parse(html, url);   // baseUri = url
    }

    /* ================================================================== */
    /* === private helpers ============================================== */
    /* ================================================================== */

    /** URI builder callback for WebClient. */
/*
    public java.net.URI buildUri(UriBuilder ub, Object... args) {
        // args[0] = cate, args[1] = partno
        return ub.path(cfg.getMpnSearchPath())
                .queryParam("cate",   args[0])
                .queryParam("partno", args[1])
                .queryParam("stype",  "1")
                .build();
    }
*/

    /* ---------- JSON -------------------------------------------------- */

/*    public List<Map<String, Object>> parseJson(String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);

        // Murata’s JSON payloads vary; cover the most common shapes
        JsonNode products =
                root.path("itemList").isMissingNode() ? root.path("ItemList") : root.path("itemList");

        if (!products.isArray()) {
            log.warn("Unexpected Murata JSON structure.");
            return Collections.emptyList();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode node : products) {
            String partNo = node.path("partNo").asText(node.path("partno").asText(""));
            if (StringUtils.isBlank(partNo)) continue;

            String url = node.path("url").asText();   // sometimes absent
            if (!StringUtils.isBlank(url))
                url = cfg.getBaseUrl() + "/en-us/products/productdetail?partno=" + partNo;

            Map<String,Object> entry = new LinkedHashMap<>();
            entry.put("mpn", partNo);
            entry.put("url", url);

            // Flatten any “specs” object if present
            if (node.has("specs")) {
                Map<String,String> specs = new LinkedHashMap<>();
                node.path("specs").fields().forEachRemaining(f ->
                        specs.put(f.getKey(), f.getValue().asText()));
                if (!specs.isEmpty()) entry.put("specifications", specs);
            }
            out.add(entry);
        }
        log.debug("Parsed {} products from Murata JSON", out.size());
        return out;
    }*/

    /**
     * Parse Murata JSON response.
     *
     * @param json raw JSON returned by the /webapi/PsdispRest endpoint
     * @return list of rows, each row = { header → value }
     */
    protected List<Map<String, Object>> parseJson(String json) {

        try {
            JsonNode result   = mapper.readTree(json).path("Result");
            List<String> hdrs = mapper.convertValue(result.path("header"),
                    new TypeReference<List<String>>() {});

            /* 1 ── column labels (“partnumber:Part Number:1:…” → “Part Number”) */
            List<String> labels = hdrs.stream()
                    .map(s -> s.split(":", 3)[1])
                    .toList();

            /* 2 ── assemble rows ------------------------------------------------ */
            List<Map<String, Object>> rows = new ArrayList<>();

            for (JsonNode p : result.at("/data/products")) {

                List<String> vals = mapper.convertValue(
                        p.path("Value"), new TypeReference<List<String>>() {});

                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < Math.min(labels.size(), vals.size()); i++) {
                    String v = vals.get(i)
                            .replaceAll("<br\\s*/?>", ", ")
                            .replaceAll("<[^>]+>", "")
                            .trim();
                    if (!v.isEmpty()) row.put(labels.get(i), v);
                }

                /* canonical helpers identical to the Selenium variant ----------- */
                String pn = row.getOrDefault("Part Number", "").toString()
                        .replace("#", "");
                row.put("mpn", pn);
                row.put("url",
                        "https://www.murata.com/en-us/products/productdetail?partno=" + pn + "%23");

                rows.add(row);
            }
            return rows;

        } catch (IOException e) {
            log.error("Cannot parse Murata PsdispRest JSON", e);
            return List.of();          // empty but non‑null
        }
    }

    protected List<Map<String,Object>> parseJsonCrossReferrence(String json) {

        try {
            JsonNode root   = mapper.readTree(json).path("Result");
            List<String> hdr = mapper.convertValue(
                    root.path("header"), new TypeReference<List<String>>() {});

            /* column labels: “field:Readable Header:…” → Readable Header */
            List<String> labels = hdr.stream()
                    .map(x -> x.split(":", 3)[1])
                    .toList();

            List<Map<String,Object>> rows = new ArrayList<>();

            for (JsonNode prod : root.at("/data/products")) {

                List<String> vals = mapper.convertValue(
                        prod.path("Value"), new TypeReference<List<String>>() {});

                Map<String,Object> row = new LinkedHashMap<>();
                for (int i = 0; i < Math.min(labels.size(), vals.size()); i++) {
                    String v = vals.get(i)
                            .replaceAll("<br\\s*/?>", ", ")
                            .replaceAll("<[^>]+>", "")
                            .trim();
                    if (!v.isEmpty()) row.put(labels.get(i), v);
                }

                // convenience extras
                String murataPn = row.getOrDefault("Murata Part Number", "").toString()
                        .replace("#", "");
                if (!murataPn.isBlank()) {
                    row.put("mpn", murataPn);
                    row.put("url", "https://www.murata.com/en-us/products/productdetail?partno=" + murataPn + "%23");
                }

                rows.add(row);
            }
            return rows;

        } catch (Exception ex) {
            log.error("Murata X‑Ref JSON parse error", ex);
            return List.of();
        }
    }

    protected Map<String, List<Map<String, Object>>> parseCrossRefJson(String rawJson) {
        JsonNode root;
        try {
            root = getMapper().readTree(rawJson);
        } catch (Exception ex) {
            throw new IllegalStateException("Malformed JSON returned by Murata API", ex);
        }

        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>(2);
        out.put("competitor", parseSection(root, "otherPsDispRest"));
        out.put("murata",      parseSection(root, "murataPsDispRest"));
        return out;
    }

// ----------------------- inside VendorHttpSearchEngine -----------------------

    private List<Map<String, Object>> parseSection(JsonNode root, String key) {

        JsonNode result = Optional.ofNullable(root.get(key))
                .map(n -> n.get("Result"))
                .orElse(null);

        if (result == null || !result.has("data")) {
            return List.of();                         // nothing to parse
        }
        boolean isMurataSection = "murataPsDispRest".equals(key);

        /* ---------- 1️⃣  build header list (human‑readable captions) ---------- */
        List<String> headers = new ArrayList<>();
        for (JsonNode h : result.withArray("header")) {       // <- NO CAST ✔
            String[] parts = h.asText().split(":", 3);
            headers.add(parts.length > 1 ? parts[1].trim() : parts[0].trim());
        }

        /* ---------- 2️⃣  build rows ------------------------------------------ */
        List<Map<String, Object>> rows = new ArrayList<>();

        String partNumber = null;
        ArrayNode products = result.path("data").withArray("products");
        for (JsonNode prod : products) {

            ArrayNode values = prod.withArray("Value");
            Map<String, Object> row = new LinkedHashMap<>();

            for (int i = 0; i < Math.min(headers.size(), values.size()); i++) {
                String caption = headers.get(i);
                String raw     = values.get(i).asText();
                String clean   = Jsoup
                        .parse(raw.replace("<br/>", " "))   // quick & safe clean‑up
                        .text()
                        .trim();

                if (!clean.isEmpty()){
                    row.put(caption, clean);
                    if ("Part Number".equals(caption)) {
                        partNumber = clean.replace("#", "");        // strip trailing '#'
                    }
                }
            }
            if (isMurataSection && partNumber != null) {
                row.put("url",
                        "https://www.murata.com/en-us/products/productdetail?partno="
                                + partNumber + "%23");
            }
            rows.add(row);
        }
        return rows;
    }


    /* ---------- HTML -------------------------------------------------- */

    public List<Map<String, Object>> parseHtml(String html) throws IOException {
        Document doc = Jsoup.parse(html);

        Element table = doc.selectFirst("table.main-table");
        if (table == null) {
            log.warn("No <table.main-table> found – HTML layout changed?");
            return Collections.emptyList();
        }

        // headers reside in the first “header-row-0” row
        List<String> headers = table.select("tr.header-row-0 th").stream()
                .map(th -> th.text().trim())
                .toList();

        Elements rows = table.select("tr[class^=row-], tr[class~=row-\\d+]");

        List<Map<String,Object>> out = new ArrayList<>();
        for (Element tr : rows) {
            Element link = tr.selectFirst("td a[href*=productdetail?partno=]");
            if (link == null) continue;

            String partNo = link.text().replace("#", "").trim();
            String url    = cfg.getBaseUrl() + link.attr("href");

            Map<String,String> specs = new LinkedHashMap<>();
            Elements tds = tr.select("td");
            for (int i = 1; i < Math.min(headers.size(), tds.size()); i++) {
                String key = headers.get(i);
                String val = tds.get(i).text().trim();
                if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(val)){
                    specs.put(key, val);
                }
            }

            Map<String,Object> entry = new LinkedHashMap<>();
            entry.put("mpn", partNo);
            entry.put("url", url);
            if (!specs.isEmpty()) entry.put("specifications", specs);
            out.add(entry);
        }

        log.debug("Parsed {} products from Murata HTML table", out.size());
        return out;
    }

}
