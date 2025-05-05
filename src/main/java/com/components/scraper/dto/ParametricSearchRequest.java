package com.components.scraper.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Request payload for performing a parametric search on a vendor’s catalog.
 * <p>
 * Encapsulates the category and optional subcategory under which to search,
 * an optional base MPN (manufacturer part number) filter, an arbitrary map
 * of additional filter parameters (which may be single values, ranges, or lists),
 * and a maximum number of results to return.
 * </p>
 * <p>
 * The {@link #getMaxResultsOrDefault()} method will fallback to a default of 100
 * if {@code maxResults} is {@code null}, less than 1, or omitted.
 * </p>
 */
@Data
public class ParametricSearchRequest {

    /**
     * Default maximum number of results when none is specified or when the provided
     * {@code maxResults} is less than 1.
     */
    private static final int DEFAULT_MAX_RESULTS = 100;

    /**
     * The main product category to search within (e.g., "Capacitors").
     * Must be a non-blank string.
     */
    @NotBlank
    private String category;

    /**
     * An optional subcategory within the main category (e.g., "Ceramic Capacitors").
     * May be {@code null} or blank if not applicable.
     */
    private String subcategory;

    /**
     * An optional manufacturer part number to further restrict results.
     * May be {@code null} or blank if not used.
     */
    private String mpn;

    /**
     * A map of arbitrary filter parameters.
     * <p>
     * Keys are parameter names as expected by the vendor’s API.
     * Values may be:
     * <ul>
     *   <li>a single scalar value (String, Number, etc.),</li>
     *   <li>a {@code Map<String, Number>} with {@code "min"} and/or {@code "max"} entries for ranges,</li>
     *   <li>or a {@code List<?>} of allowed values.</li>
     * </ul>
     * </p>
     * Must not be {@code null}; an empty map indicates no additional filters.
     */
    @NotNull
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * The maximum number of results to return.
     * <p>
     * If omitted or less than 1, the {@link #getMaxResultsOrDefault()} method
     * will return a default of 100.
     * </p>
     */
    @Min(1)
    private Integer maxResults;

    /**
     * Returns the maximum number of results to return, falling back to 100
     * if {@code maxResults} is {@code null} or less than 1.
     *
     * @return the effective result limit, guaranteed to be at least 1
     */
    public int getMaxResultsOrDefault() {
        return (maxResults != null && maxResults > 0) ? maxResults : DEFAULT_MAX_RESULTS;
    }
}
