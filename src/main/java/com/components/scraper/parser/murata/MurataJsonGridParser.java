package com.components.scraper.parser.murata;

import com.components.scraper.parser.JsonGridParser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Murata’s JSON-based grid responses into a list of row-maps.
 * <p>
 * <ul>
 *   <li>Extracts column labels from {@code /Result/header}.</li>
 *   <li>Extracts product rows from {@code /Result/data/products}.</li>
 *   <li>Maps each {@code Value} array to a {@code Map<String,Object>} by header name.</li>
 *   <li>Appends a {@code url} field pointing to the Murata detail page for convenience.</li>
 * </ul>
 * </p>
 */
@Component("murataGridParser")
public class MurataJsonGridParser implements JsonGridParser {

    /**
     * JSON-Pointer to the array of header metadata.
     * <p>Can be externalized to properties if the response structure changes.</p>
     */
    @SuppressWarnings("java:S1075") // allow hard-coded JSON path
    private static final String JSON_PATH_HEADER = "/Result/header";

    /**
     * JSON-Pointer to the array of product rows.
     * <p>Can be externalized to properties if the response structure changes.</p>
     */
    @SuppressWarnings("java:S1075") // allow hard-coded JSON path
    private static final String JSON_PATH_PRODUCTS = "/Result/data/products";

    /**
     * Key under each product node containing its values.
     */
    private static final String JSON_KEY_VALUE = "Value";

    /**
     * Index of the header segment representing the display name
     * after splitting on the delimiter.
     */
    private static final int HEADER_NAME_INDEX = 1;

    /**
     * Delimiter between header metadata segments.
     */
    private static final String HEADER_DELIMITER = ":";

    /**
     * Display name used for the part-number column.
     */
    private static final String COLUMN_PART_NUMBER = "Part Number";

    /**
     * Character appended to Murata part numbers; URL-encoded as "%23".
     */
    private static final String PART_NUMBER_SUFFIX = "#";

    /**
     * Template for generating the Murata product detail URL.
     * <p>The {@code %s} placeholder is replaced by the part number
     * (without {@code #}), and the literal {@code "%23"} encodes the suffix.</p>
     */
    private static final String DETAIL_URL_TEMPLATE =
            "https://www.murata.com/en-us/products/productdetail?partno=%s%%23";

    /**
     * Parses the given JSON tree into a list of product rows.
     * <p>
     * This method will:
     * <ol>
     *   <li>Locate the header definitions at {@link #JSON_PATH_HEADER} and extract human-readable column names.</li>
     *   <li>Locate the product value arrays at {@link #JSON_PATH_PRODUCTS}.</li>
     *   <li>For each product, map header→cell-value into a {@code Map<String,Object>}.</li>
     *   <li>Append a convenience {@code url} entry per row, linking to the Murata detail page.</li>
     * </ol>
     * </p>
     *
     * @param root the JSON root node of Murata’s grid response
     * @return a list of row-maps; each map keys by column name and maps to its value, plus an extra "url" key
     */
    @Override
    public List<Map<String, Object>> parse(final JsonNode root) {
        JsonNode headerNode = root.at(JSON_PATH_HEADER);
        JsonNode productsNode = root.at(JSON_PATH_PRODUCTS);

        // If either expected node is missing or not an array, no rows to parse
        if (!headerNode.isArray() || !productsNode.isArray()) {
            return Collections.emptyList();
        }

        // Extract display names from header metadata
        List<String> headers = new ArrayList<>();
        headerNode.forEach(h -> {
            String[] parts = h.asText().split(HEADER_DELIMITER);
            if (parts.length > HEADER_NAME_INDEX) {
                headers.add(parts[HEADER_NAME_INDEX]);
            } else {
                headers.add(parts[0]);
            }
        });

        List<Map<String, Object>> rows = new ArrayList<>(productsNode.size());

        // Map each product's Value array to a row map
        productsNode.forEach(p -> {
            JsonNode values = p.get(JSON_KEY_VALUE);
            Map<String, Object> row = new LinkedHashMap<>(headers.size() + 1);

            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                JsonNode cell = values.get(i);
                row.put(header, cell != null ? cell.asText() : null);
            }

            // Add detail URL for convenience
            String partNoRaw = String.valueOf(row.get(COLUMN_PART_NUMBER));
            String partNo = partNoRaw.replace(PART_NUMBER_SUFFIX, "");
            String url = String.format(DETAIL_URL_TEMPLATE, partNo);
            row.put("url", url);
            rows.add(row);
        });

        return rows;
    }
}
