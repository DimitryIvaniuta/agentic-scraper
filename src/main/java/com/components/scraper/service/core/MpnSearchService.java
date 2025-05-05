package com.components.scraper.service.core;

import java.util.List;
import java.util.Map;

/**
 * Defines a contract for searching products by their Manufacturer Part Number (MPN).
 * <p>
 * Implementations of this interface encapsulate the logic necessary to query
 * a specific vendor's catalog—whether via HTTP APIs, web scraping, or other mechanisms—
 * and return structured product data.
 * </p>
 */
public interface MpnSearchService {

    /**
     * Searches for products matching the provided manufacturer part number (MPN).
     * <p>
     * The returned list contains one entry per matching product. Each map represents
     * a single product record, with keys corresponding to attribute names (e.g., "Part Number",
     * "Capacitance", "Rated Voltage") and values holding the associated data.
     * </p>
     *
     * @param mpn
     *     the exact manufacturer part number to look up; must not be {@code null} or blank
     * @return
     *     a non-null, possibly empty {@link List} of result records (each a {@link Map}
     *     of attribute names to values). Never returns {@code null}.
     * @throws IllegalArgumentException
     *     if the given {@code mpn} is {@code null}, empty, or otherwise invalid
     * @throws RuntimeException
     *     if an unexpected error occurs while performing the search (e.g., network issues,
     *     parsing failures, etc.)
     */
    List<Map<String, Object>> searchByMpn(String mpn);
}
