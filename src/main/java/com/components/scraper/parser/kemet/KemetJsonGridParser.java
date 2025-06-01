package com.components.scraper.parser.kemet;

import com.components.scraper.parser.JsonGridParser;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <h2>KEMET JSON Parser</h2>
 *
 * <p>This version flattens <strong>all</strong> {@code parameterValues[*]}
 * into an easy‑to‑consume structure where each {@code parameterName} is used
 * as the JSON property key and its <em>array</em> of
 * {@code formattedValue}s becomes the property value.</p>
 *
 * <p>Example output for a single part:</p>
 * <pre>{
 *   "MPN": "GPC16.5104K630C31TV44",
 *   "Capacitance": ["0.1 uF"],
 *   "Tolerance":   ["10%"],
 *   "Voltage DC":  ["630 VDC"],
 *   …
 * }</pre>
 */
@Component("kemetGridParser")
@Slf4j
public final class KemetJsonGridParser implements JsonGridParser {

    private static final String KEY_PARTS = "detectedUniqueParts";

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Map<String, Object>> parse(final JsonNode root) {
        if (root == null || !root.has(KEY_PARTS) || !root.get(KEY_PARTS).isArray()) {
            return List.of();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode rawPart : root.get(KEY_PARTS)) {
            Map<String, Object> row = new LinkedHashMap<>();

            // Canonical part number & a few administrative flags
            row.put("MPN", textOrNull(rawPart, "displayPn"));
            row.put("obsolete", rawPart.path("obsolete").asBoolean(false));
            row.put("rohsExceptions", rawPart.path("hasRoHSExceptions").asBoolean(false));

            // Flatten every parameterName → [formattedValue, …]
            if (rawPart.has("parameterValues") && rawPart.get("parameterValues").isArray()) {
                rawPart.get("parameterValues").forEach(p -> {
                    String name = textOrNull(p, "parameterName");
                    if (!StringUtils.hasText(name)) return;

                    List<String> values = new ArrayList<>();
                    JsonNode vals = p.path("parameterValues");
                    if (vals.isArray()) {
                        vals.forEach(v -> {
                            String fv = textOrNull(v, "formattedValue");
                            if (StringUtils.hasText(fv)) values.add(fv);
                        });
                    }
                    if (!values.isEmpty()) {
                        row.put(name, Collections.unmodifiableList(values));
                    }
                });
            }

            rows.add(Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableList(rows);
    }

    private static String textOrNull(final JsonNode node, final String field) {
        JsonNode n = (node == null) ? null : node.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }
}
