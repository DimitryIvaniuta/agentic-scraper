package com.components.scraper.parser;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
/**
 * Converts a vendor‑specific JSON payload into a list of rows.
 */
@FunctionalInterface
public interface JsonGridParser {

    /**
     * @param root complete JSON document returned by the vendor
     * @return     List<Map<Header,Value>> – may be empty but never {@code null}
     */
    List<Map<String, Object>> parse(JsonNode root);

}

