package com.components.scraper.parser.murata;

import com.components.scraper.parser.JsonGridParser;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

@Component("murataGridParser")
public class MurataJsonGridParser implements JsonGridParser {

    @Override
    public List<Map<String, Object>> parse(JsonNode root) {

        JsonNode headerNode   = root.at("/Result/header");
        JsonNode productsNode = root.at("/Result/data/products");

        if (!headerNode.isArray() || !productsNode.isArray()) {
            return List.of();
        }

        List<String> headers = new ArrayList<>();
        headerNode.forEach(h -> headers.add(h.asText().split(":")[1]));

        List<Map<String, Object>> rows = new ArrayList<>();

        productsNode.forEach(p -> {
            JsonNode v = p.get("Value");
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                row.put(headers.get(i), v.get(i).asText());
            }
            // convenience – add Murata detail URL
            String pn = String.valueOf(row.get("Part Number")).replace("#", "");
            row.put("url",
                    "https://www.murata.com/en-us/products/productdetail?partno=" + pn + "%23");

            rows.add(row);
        });

        return rows;
    }

}
