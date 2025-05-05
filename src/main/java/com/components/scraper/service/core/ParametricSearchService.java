package com.components.scraper.service.core;


import java.util.List;
import java.util.Map;

/**
 * Defines a contract for performing parametric (filter-based) product searches.
 * <p>
 * Implementations of this interface support querying a vendor’s catalog
 * by category hierarchy and arbitrary filter criteria, returning structured
 * product data matching those parameters.
 * </p>
 */
public interface ParametricSearchService {

    /**
     * Searches for products by category, optional subcategory, and a set of parameter filters.
     * <p>
     * The {@code category} and {@code subcategory} define the product classification
     * (e.g. "Capacitors" → "Ceramic"). The {@code parameters} map contains one or more
     * filtering criteria, whose values may be:
     * <ul>
     *   <li>a single scalar (e.g. {@code "capacitance": 10})</li>
     *   <li>a numeric or string range map (e.g. {@code "capacitance": Map.of("min", 1, "max", 100)})</li>
     *   <li>a list of discrete values (e.g. {@code "package": List.of("0805", "0603")})</li>
     * </ul>
     * The {@code maxResults} parameter caps the number of records returned.
     * </p>
     *
     * @param category
     *     the primary product category (must not be {@code null} or blank)
     * @param subcategory
     *     the optional subcategory within the primary category, or {@code null}/blank
     *     to search the entire category
     * @param parameters
     *     a non-{@code null} {@link Map} of filter names to values; may be empty
     * @param maxResults
     *     the maximum number of matching product records to return; must be {@code >= 1}
     * @return
     *     a non-null, possibly empty {@link List} of product records, where each record
     *     is represented as a {@link Map} of attribute names to their values
     * @throws IllegalArgumentException
     *     if {@code category} is {@code null} or blank, {@code parameters} is {@code null},
     *     or {@code maxResults < 1}
     * @throws RuntimeException
     *     if an unexpected error occurs during the search operation (e.g., network errors,
     *     data parsing failures, etc.)
     */
    List<Map<String, Object>> searchByParameters(
            String category,
            String subcategory,
            Map<String, Object> parameters,
            int maxResults);
}
