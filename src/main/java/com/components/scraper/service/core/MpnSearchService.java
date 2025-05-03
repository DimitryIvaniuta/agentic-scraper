package com.components.scraper.service.core;

import java.util.List;
import java.util.Map;

public interface MpnSearchService {
    /**
     * @param mpn MPN part number
     */
    List<Map<String, Object>> searchByMpn(String mpn);
}
