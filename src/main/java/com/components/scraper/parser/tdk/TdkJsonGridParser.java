package com.components.scraper.parser.tdk;

import com.components.scraper.parser.JsonGridParser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component("tdkGridParser")
public class TdkJsonGridParser implements JsonGridParser {
    @Override
    public List<Map<String, Object>> parse(JsonNode root) {
        return new ArrayList<>();
    }
}
