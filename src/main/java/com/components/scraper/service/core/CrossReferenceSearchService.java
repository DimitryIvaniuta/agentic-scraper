package com.components.scraper.service.core;

import java.util.List;
import java.util.Map;

/**
 * Service interface for performing cross-reference lookups between competitor part numbers and vendor part numbers.
 *
 * <p>Implementations of this interface should query the underlying vendor-specific data source (e.g., HTTP API,
 * database, or web scraping) to retrieve mappings from a competitor's part number to one or more vendor part numbers,
 * optionally scoped by a category path that refines the search context.</p>
 */
public interface CrossReferenceSearchService {

    /**
     * Searches for cross-reference mappings between a competitor's part number and the vendor's own part numbers.
     *
     * @param competitorPart the competitor's part number to look up; must be non-null and non-blank
     * @param category       the category path (e.g., ["Inductors", "Power Inductors"]) that refines the
     *                       search context; may be empty or null if not applicable
     * @return a list of result records, each represented as a map of attribute names to values. Typical keys include
     * "competitorPart", "vendorPart", and additional spec fields as defined by the provider. Never null;
     * an empty list indicates no matches found.
     * @throws IllegalArgumentException if {@code competitorPart} is null or blank, or if the category list is invalid
     */
    List<Map<String, Object>> searchByCrossReference(String competitorPart, List<String> category);
}
