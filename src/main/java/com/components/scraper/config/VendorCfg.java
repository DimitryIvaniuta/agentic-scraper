package com.components.scraper.config;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds configuration properties for a given vendor's scraping services.
 * <p>
 * Each instance encapsulates the various endpoint URLs and
 * category mappings required to perform Manufacturer Part Number (MPN)
 * searches, cross-reference lookups, and parametric searches
 * against the vendor's public APIs.
 * </p>
 */
@Getter
@Setter
public class VendorCfg {

    /**
     * The base URL to which all vendor-specific API paths are relative.
     * <p>For example, "https://www.murata.com".</p>
     */
    private String baseUrl;

    /**
     * The path (relative to {@link #baseUrl}) used to perform MPN searches.
     * <p>For example, "/webapi/PsdispRest" or "/api/search/mpn".</p>
     */
    private String mpnSearchPath;

    /**
     * The path (relative to {@link #baseUrl}) used to perform cross-reference lookups.
     * <p>For example, "/webapi/SearchCrossReference".</p>
     */
    private String crossRefUrl;

    /**
     * The default category code to use when extracting MPN prefix does not match
     * any explicit mapping in {@link #categories}.
     */
    private String defaultCate;

    /**
     * The default category code to use when extracting MPN prefix does not match
     * any explicit mapping in {@link #crossRefCategories}.
     */
    private String crossRefDefaultCate;

    /**
     * The path (relative to {@link #baseUrl}) used to perform parametric searches.
     * <p>For example, "/webapi/PsdispRest" for Murata parametric search interface.</p>
     */
    private String parametricSearchUrl;

    /**
     * A mapping from MPN prefix (first 3 characters) to vendor-specific category code
     * for MPN-based searches.
     * <p>Key is the uppercase 3-character prefix, value is the "cate" parameter.</p>
     */
    private Map<String, String> categories = new HashMap<>();

    /**
     * A mapping from MPN prefix (first 3 characters) to vendor-specific category code
     * for cross-reference lookups.
     * <p>Key is the uppercase 3-character prefix, value is the "cate" parameter.</p>
     */
    private Map<String, String> crossRefCategories = new HashMap<>();
}


