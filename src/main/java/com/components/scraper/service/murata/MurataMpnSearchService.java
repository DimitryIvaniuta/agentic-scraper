package com.components.scraper.service.murata;

import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.parser.JsonGridParser;
import com.components.scraper.service.core.VendorSearchEngine;
import com.components.scraper.service.core.MpnSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Service("murataMpnSvc")
public class MurataMpnSearchService
        extends VendorSearchEngine implements MpnSearchService {

    private final JsonGridParser parser;

    public MurataMpnSearchService(
            @Qualifier("murataGridParser") JsonGridParser parser,
            VendorConfigFactory factory,
            RestClient client
    ) {
        super(factory.forVendor("murata"), client);
        this.parser = parser;
    }

    @Override
    public List<Map<String, Object>> searchByMpn(String mpn) {
        String cate = cateFromPartNo(mpn);
        String cleaned = mpn.trim();
//        JsonNode root = safeGet(ub -> ub
//                .scheme("https").host(getCfg().getBaseUrl())
//                .path(getCfg().getMpnSearchPath())
//                .queryParam("cate",   cate)
//                .queryParam("partno", mpn)
//                .queryParam("stype",  1)
//                .queryParam("lang",   "en-us")
//                .build());
        JsonNode root = safeGet(ub -> {
            URI base = URI.create(getCfg().getBaseUrl());   // e.g. https://www.murata.com

            // apply scheme + host (and port if present)
            ub = ub.scheme(base.getScheme())
                    .host(base.getHost());
            if (base.getPort() != -1) {                     // port is -1 when absent
                ub = ub.port(base.getPort());
            }

            // prepend any path segment that is already part of base‑url
            String basePath = base.getPath();
            if (StringUtils.hasText(basePath) && !"/".equals(basePath)) {
                ub = ub.path(basePath);                     // e.g. "" or "/"
            }

            /* endpoint + query parameters */
            return ub.path(getCfg().getMpnSearchPath())     // /webapi/PsdispRest
                    .queryParam("cate",   cate)
                    .queryParam("partno", cleaned)
                    .queryParam("stype",  1)
                    .queryParam("lang",   "en-us")
                    .build();
        });
        return parser.parse(root).stream().toList();
    }
}
