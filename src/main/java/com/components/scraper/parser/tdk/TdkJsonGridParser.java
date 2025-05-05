package com.components.scraper.parser.tdk;

import com.components.scraper.parser.JsonGridParser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component("tdkGridParser")
public class TdkJsonGridParser implements JsonGridParser {

    /**
     * Initial TDK parser.
     *
     * @param root the JSON root node of TDK’s grid response
     * @return a list of row-maps; each map keys by column name and maps to its value, plus an extra "url" key
     */
    @Override
    public List<Map<String, Object>> parse(final JsonNode root) {
        return new ArrayList<>();
    }
}
