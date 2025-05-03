package com.components.scraper.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class ParametricSearchRequest {

    @NotBlank
    private String vendor;

    @NotBlank
    private String category;

    private String subcategory;

    private String mpn;

    /** Arbitrary filter map (scalar, range‑map, or list values). */
    @NotNull
    private Map<String, Object> parameters = new HashMap<>();

    @Min(1) private Integer maxResults;

    /** Defaults to 100 when the field is omitted / null / &lt;1. */
    public int getMaxResultsOrDefault() {
        return (maxResults != null && maxResults > 0) ? maxResults : 100;
    }
}