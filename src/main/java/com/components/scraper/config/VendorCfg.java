package com.components.scraper.config;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class VendorCfg {
    private String baseUrl;
    private String mpnSearchPath;
    private String crossRefUrl;
    private String defaultCate;
    private String crossRefDefaultCate;
    private Map<String, String> categories = new HashMap<>();
    private Map<String, String> crossRefCategories = new HashMap<>();
}

