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

            // Mandatory fields – always present for a valid part
            row.put("MPN", textOrNull(rawPart, "displayPn"));
            row.put("mfgId", rawPart.path("mfgId").asInt(-1));
            row.put("obsolete", rawPart.path("obsolete").asBoolean(false));
            row.put("rohsExceptions", rawPart.path("hasRoHSExceptions").asBoolean(false));

            // Datasheet hyperlink if provided
            String specsheet = textOrNull(rawPart, "specsheetLink");
            if (StringUtils.hasText(specsheet)) {
                row.put("datasheet", specsheet);
            }

            // Aliases – list of alternative part numbers (optional)
            if (rawPart.has("aliases") && rawPart.get("aliases").isArray()) {
                List<String> aliases = new ArrayList<>();
                rawPart.get("aliases").forEach(a -> aliases.add(textOrNull(a, "displayPn")));
                if (!aliases.isEmpty()) {
                    row.put("aliases", aliases);
                }
            }

            // Parameter bucket (Capacitance, Voltage, Tolerance, …)
            if (rawPart.has("parameters") && rawPart.get("parameters").isArray()) {
                rawPart.get("parameters").forEach(p -> {
                    String name = textOrNull(p, "name");
                    if (!StringUtils.hasText(name)) {
                        return; // continue lambda
                    }
                    JsonNode values = p.path("parameterValues");
                    if (values.isArray() && values.size() > 0) {
                        String val = textOrNull(values.get(0), "name");
                        if (StringUtils.hasText(val)) {
                            row.put(name, val);
                        }
                    }
                });
            }

            rows.add(Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableList(rows);
    }

    /** Null‑safe helper. */
    private static String textOrNull(final JsonNode node, final String field) {
        JsonNode n = (node == null) ? null : node.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }
}
