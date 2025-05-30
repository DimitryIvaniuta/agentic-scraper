package com.components.scraper.parser.tdk;

import com.components.scraper.parser.JsonGridParser;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <h2>TDK JSON&nbsp;Grid Parser</h2>
 * <p>Converts the hybrid <em>JSON + HTML</em> payload returned by
 * <code>/pdc_api/en/search/list/search_result</code> into a list of plain
 * {@code Map&lt;String,Object&gt;} instances, ready for downstream mapping or
 * storage.  The structure of TDK’s response is:</p>
 * <pre>{@code
 * {
 *     "results": "<table>…</table>",          // HTML fragment
 *     "columns": [ {"column_order":0,"column_name":"Part No."}, … ],
 *     …
 * }
 * }</pre>
 * <p>The table itself contains decorative rows at the top and bottom; columns
 * are referenced indirectly by <em>order × 10</em>.  This parser applies the
 * following business rules:</p>
 * <ol>
 *     <li><strong>Row filtering:</strong> discard the first two header rows and
 *         the last two empty rows.</li>
 *     <li><strong>Column window:</strong> ignore the first two and last two
 *         {@code &lt;td&gt;} cells (checkboxes, icons).</li>
 *     <li><strong>Datasheet capture:</strong> replace the “Catalog / Data<br>
 *         Sheet” column value with the absolute PDF URL if present.</li>
 *     <li><strong>Detail URL:</strong> extract the absolute anchor @href from
 *         the “Part No.” column into an extra field {@code url}.</li>
 *     <li><strong>Deduplication:</strong> drop duplicate rows based on
 *         identical Part Numbers to guard against vendor-side rendering quirks.
 *     </li>
 * </ol>
 * <p>All literals are declared as constants; control flow deliberately uses at
 * most <strong>one</strong> {@code continue} and zero {@code break} statements
 * to comply with static‑analysis constraints.</p>
 */
@Component("tdkGridParser")
@Slf4j
public final class TdkJsonGridParser implements JsonGridParser {

    /** Column label used by TDK for the manufacturer part number. */
    private static final String COL_PART_NO = "Part No.";
    /** Column label that holds a datasheet hyperlink (PDF). */
    private static final String COL_DATASHEET = "Catalog / Data Sheet";
    /** Multiplier applied by TDK to {@code column_order}. */
    private static final int ORDER_MULTIPLIER = 10;

    /**
     * Transform TDK’s grid‑JSON response into a collection of row maps.
     * <p>Implementation notes:
     * <ul>
     *   <li>The first two and last two table rows are decorative; skipped.</li>
     *   <li>The <em>very first data row</em> may be completely empty — it is
     *       ignored by detecting a blank Part No. + blank cells.</li>
     *   <li>Exactly <strong>one</strong> {@code continue} statement is used
     *       (to omit rows that contain zero {@code &lt;td&gt;} elements).</li>
     * </ul>
     * </p>
     *
     * @param root JSON root node from TDK API (never modified)
     * @return immutable list of maps containing cleaned grid rows
     */
    @Override
    public List<Map<String, Object>> parse(final JsonNode root) {
        if (root == null || !root.hasNonNull("results")) {
            return List.of();
        }
        String html = root.path("results").asText();
        if (!StringUtils.hasText(html)) {
            return List.of();
        }

        Map<Integer, String> orderToHeader = buildHeaderMap(root.path("columns"));

        Document doc = Jsoup.parse(html);
        List<Element> allRows = doc.select("tr");
        if (allRows.size() <= 1) {
            return List.of();
        }

        Set<String> seenPartNos = new HashSet<>();
        List<Map<String, Object>> parsedRows = new ArrayList<>(allRows.size());

        for (int idx = 2; idx < allRows.size(); idx++) {
            Element tr = allRows.get(idx);
            Elements tds = tr.select("td");
            if (tds.isEmpty()) {
                continue; // single allowed continue
            }

            Map<String, Object> row = new LinkedHashMap<>();
            String partNoValue = null;

            // Skip decorative TDs 0,1 and last 2
            for (int col = 2; col < tds.size(); col++) {
                String header = orderToHeader.getOrDefault(col * ORDER_MULTIPLIER, "col_" + col);
                Element td = tds.get(col);

                if (COL_PART_NO.equalsIgnoreCase(header)) {
                    partNoValue = extractPartNo(td, row);
                } else if (COL_DATASHEET.equalsIgnoreCase(header)) {
                    extractDatasheet(td, row);
                } else {
                    row.put(header, td.text());
                }
            }

            // Skip the very first data row if it is completely blank (issue #124)
            boolean firstRowIsPlaceholder = idx == 0 && (partNoValue == null || partNoValue.isBlank())
                    && row.values().stream().allMatch(v -> Objects.toString(v, "").isBlank());

            boolean isUnique = partNoValue != null;
            if (!firstRowIsPlaceholder && isUnique && !row.isEmpty()) {
                parsedRows.add(Collections.unmodifiableMap(row));
            }
        }
        return Collections.unmodifiableList(parsedRows);
    }

    private static Map<Integer, String> buildHeaderMap(final JsonNode columns) {
        if (columns == null || !columns.isArray()) {
            return Map.of();
        }
        Map<Integer, String> map = new HashMap<>();
        for (JsonNode n : columns) {
            map.put(n.path("column_order").asInt(), n.path("column_name").asText());
        }
        return map;
    }

    private static String extractPartNo(final Element td, final Map<String, Object> row) {
        Element anchor = td.selectFirst("a[href]");
        String text = (anchor != null) ? anchor.text() : td.text();
        row.put(COL_PART_NO, text);
        if (anchor != null) {
            row.put("url", anchor.absUrl("href"));
        }
        return text;
    }

    private static void extractDatasheet(final Element td, final Map<String, Object> row) {
        Element pdf = td.selectFirst("a[href$=.pdf]");
        if (pdf != null) {
            row.put(COL_DATASHEET, pdf.absUrl("href"));
        }
    }
}

