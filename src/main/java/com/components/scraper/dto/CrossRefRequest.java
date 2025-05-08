package com.components.scraper.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request payload for cross-reference searches.
 * <p>
 * Encapsulates the competitor's part number and an optional category path
 * to narrow down the cross-reference lookup on the target vendor's catalog.
 * </p>
 *
 * @param competitorMpn the competitor's manufacturer part number to find equivalents for;
 *                      must not be blank
 * @param categoryPath  an ordered list of category and subcategory names that define
 *                      the search context; may be empty or null if no category filtering is required
 */
public record CrossRefRequest(
        @NotBlank
        String competitorMpn,
        List<String> categoryPath
) {
}
