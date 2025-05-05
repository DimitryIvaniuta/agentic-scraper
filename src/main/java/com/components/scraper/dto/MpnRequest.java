package com.components.scraper.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for MPN (Manufacturer Part Number) searches.
 * <p>
 * Encapsulates the part number to look up and the vendor identifier,
 * which determines which {@code MpnSearchService} implementation will be used.
 * </p>
 *
 * @param mpn    the manufacturer part number to search for; must not be blank
 * @param vendor the vendor key (e.g., "murata", "tdk") identifying which
 *               MPN search service to invoke; must not be blank
 */
public record MpnRequest(
        @NotBlank String mpn,
        @NotBlank String vendor
) {}
