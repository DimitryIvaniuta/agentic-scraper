package com.components.scraper.service.core;


import java.util.List;
import java.util.Map;

/**
 * All vendor‑specific parametric search engines must implement this interface.
 */
public interface ParametricSearchService {

    /**
     * Perform a parametric search.
     *
     * @param category    main family / prefix – must not be {@code null}
     * @param subcategory may be {@code null}
     * @param parameters  never {@code null}; scalar / range / list values allowed
     * @param maxResults  caller’s hard limit (implementation may cap further)
     */
    List<Map<String, Object>> searchByParameters(
            String category,
            String subcategory,
            Map<String, Object> parameters,
            int maxResults);
}