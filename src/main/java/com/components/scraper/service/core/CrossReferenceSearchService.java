package com.components.scraper.service.core;

import java.util.List;
import java.util.Map;

public interface CrossReferenceSearchService {
    /**
     * @param competitorPart competitor MPN
     */
    List<Map<String, Object>> searchByCrossReference(String competitorPart, List<String> category);
}
