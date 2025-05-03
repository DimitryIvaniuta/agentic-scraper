package com.components.scraper.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Immutable Cross-Ref search request body wrapper.
 */
public record CrossRefRequest(@NotBlank String competitorMpn, List<String> categoryPath) {}