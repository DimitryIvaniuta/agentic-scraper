package com.components.scraper.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Immutable mpn search request body wrapper.
 */
public record MpnRequest(@NotBlank String mpn, @NotBlank String vendor) {}
