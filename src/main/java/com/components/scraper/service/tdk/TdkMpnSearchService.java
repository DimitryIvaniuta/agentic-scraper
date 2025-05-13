package com.components.scraper.service.tdk;

import com.components.scraper.ai.LLMHelper;
import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.MpnSearchService;
import com.components.scraper.service.core.VendorSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TDK implementation – scrapes product.tdk.com.
 */
@Slf4j
@Service("tdkMpnSvc")
@ConditionalOnProperty(prefix = "scraper.configs.tdk", name = "enabled", havingValue = "true", matchIfMissing = true)
//@RequiredArgsConstructor
public class TdkMpnSearchService extends VendorSearchEngine implements MpnSearchService {


//    /* =====  CONSTANTS  ===== */
//    private static final URI SEARCH_URI = URI.create("https://product.tdk.com/pdc_api/en/search/list/search_result");
//    private static final String ENTRY_PAGE = "https://product.tdk.com/en/search/list";

    /* =====  COLLABORATORS  ===== */
//    private final WebClient.Builder webClientBuilder;
//    private final ScraperProperties scraperProps;

    /* =====  STATE  ===== */
    private final RestClient client;

    private static final String DEFAULT_SITE = "FBNXDO0R";
    private static final String DEFAULT_GROUP = "tdk_pdc_en";
    private static final String DEFAULT_DESIGN = "producttdkcom-en";

    private final AtomicReference<String> site    = new AtomicReference<>();
    private final AtomicReference<String> group   = new AtomicReference<>();
    private final AtomicReference<String> design  = new AtomicReference<>();
//    private Bucket rateLimiter;                            // 1 bucket per service instance

    /**
     * Constructs the Murata MPN search service.
     *
     * @param parser    JSON-grid parser bean qualified as "murataGridParser"
     * @param factory   factory for loading Murata vendor configuration
     * @param builder    configured Webclient builder
     * @param llmHelper LLMHelper to ask ChatGPT for the vendor’s real “cate” code
     */
    public TdkMpnSearchService(
            @Qualifier("tdkGridParser") final JsonGridParser parser,
            final VendorConfigFactory factory,
            final WebClient.Builder builder,
            final LLMHelper llmHelper,
            @Qualifier("scraperObjectMapper") ObjectMapper om,
            RestClient client
    ) {
        super(factory.forVendor("tdk"), builder, llmHelper, parser, om);
        this.client = client;
    }

    @Override
    public String vendor() {
        return "TDK";
    }

    @Override
    public List<Map<String, Object>> searchByMpn(String mpn) {
        String cleaned = mpn.trim();

        // Prepare constant form fields once (site, group, design, etc.)
        MultiValueMap<String, String> baseForm = new LinkedMultiValueMap<>();
        baseForm.add("site", DEFAULT_SITE);
        baseForm.add("charset", "UTF-8");
        baseForm.add("group", DEFAULT_GROUP);
        baseForm.add("design", DEFAULT_DESIGN);
        baseForm.add("fromsyncsearch", "1");
        baseForm.add("_l", String.valueOf(getCfg().getPageSize()));
        baseForm.add("_c", "part_no-part_no");
        baseForm.add("_d", "0");


        URI endpoint = buildUri(getCfg().getBaseUrl(), getCfg().getMpnSearchPath(), null);

//        List<Map<String, Object>> aggregated = new ArrayList<>();

//        for (int page = 1; page <= getCfg().getPageSize(); page++) {
//            baseForm.set("_p", String.valueOf(page));
            baseForm.set("pn", cleaned);

            JsonNode rsp = safePost(endpoint, baseForm);
//            if (rsp == null || rsp.isEmpty()) {
//                break;  // no response, abort
//            }

            List<Map<String,Object>> rows = getParser().parse(rsp);
//            List<Map<String, Object>> hits = parsePage(rsp);
//            if (rows.isEmpty()){
//                break;
//            }

//            aggregated.addAll(rows);
//            if (rows.size() < getCfg().getPageSize()) {
//                break;       // last page was partial → no more pages
//            }
//        }

        return rows;
    }

    protected JsonNode safePost1(URI uri, MultiValueMap<String, String> form) {
        try {
            log.info("start safe post1");
            JsonNode node = client.post().uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            log.info("start safe post2");
            return node;
        } catch (HttpServerErrorException ex) {
            log.warn("TDK upstream {} returned {}: {} → empty JSON",
                    uri, ex.getStatusCode(), ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.error("safePost failed for {} → empty JSON", uri, ex);
        }
        return JsonNodeFactory.instance.objectNode();
    }

    private List<Map<String, Object>> parsePage3(JsonNode root) {
        if (!root.hasNonNull("results")) return List.of();

        String html = root.path("results").asText();
        if (!StringUtils.hasText(html)) return List.of();

        // Build column‑order map first
        Map<Integer, String> idxToHeader = new HashMap<>();
        for (JsonNode col : root.path("columns")) {
            int order = col.path("column_order").asInt();
            String name = col.path("column_name").asText();
            idxToHeader.put(order, name);
        }

        Document table = Jsoup.parse(html);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Element tr : table.select("tr")) {
            Elements tds = tr.select("td");
            if (tds.isEmpty()) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < tds.size(); i++) {
                String header = idxToHeader.getOrDefault(i * 10, "col_" + i);
                row.put(header, tds.get(i).text());
            }

            // Datasheet link if present
            Element pdf = tr.selectFirst("a[href$=.pdf]");
            if (pdf != null) {
                row.put("datasheet", pdf.absUrl("href"));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Parse one page and apply business rules:
     * <ul>
     *     <li>Skip the first two rows (TDK renders two header rows).</li>
     *     <li>Skip the last two rows if they are empty.</li>
     *     <li>For "Catalog / Data Sheet" column put the PDF URL as value.</li>
     *     <li>Return <strong>only</strong> the body rows—no column metadata.</li>
     * </ul>
     */
    private List<Map<String, Object>> parsePage(JsonNode root) {
        if (!root.hasNonNull("results")) return List.of();
        String html = root.path("results").asText();
        if (!StringUtils.hasText(html)) return List.of();

        // Build header index map first
        Map<Integer, String> idxToHeader = root.path("columns").isArray()
                ? buildHeaderMap(root.path("columns"))
                : Map.of();

        Document doc = Jsoup.parse(html);
        List<Element> rows = doc.select("tr");
        if (rows.size() <= 1) return List.of(); // no data rows

        List<String> productsFoundCache = new ArrayList<>();
        List<Map<String, Object>> parsed = new ArrayList<>();
        for (int j=2; j<rows.size(); j++) {
            Element tr = rows.get(j);
            Elements tds = tr.select("td");
            if (tds.isEmpty()) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 2; i < tds.size()-2; i++) {
                String header = idxToHeader.getOrDefault(i * 10, "col_" + i);
                if ("Part No.".equalsIgnoreCase(header)) {
                    Element partNo = tds.get(i).selectFirst("a[href]");
                    if (partNo != null && productsFoundCache.contains(partNo.text())) {
                        break;
                    }else if(partNo!=null){
                        productsFoundCache.add(partNo.text());
                    }

                }
                // Special handling for Datasheet column
                if ("Catalog / Data Sheet".equalsIgnoreCase(header)) {
                    Element pdf = tds.get(i).selectFirst("a[href$=.pdf]");
                    if (pdf != null) {
                        row.put("Catalog / Data Sheet", pdf.absUrl("href"));
                    }
                    continue; // done with this cell
                }
                row.put(header, tds.get(i).text());
            }
            parsed.add(row);
        }
        return parsed;
    }

    private static Map<Integer, String> buildHeaderMap(JsonNode columns) {
        Map<Integer, String> map = new HashMap<>();
        for (JsonNode n : columns) {
            map.put(n.path("column_order").asInt(), n.path("column_name").asText());
        }
        return map;
    }
}
