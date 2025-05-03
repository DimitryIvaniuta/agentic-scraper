package com.components.scraper.service;

import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.service.core.LlmHelper;
import com.components.scraper.service.core.VendorHttpSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class MurataHttpCrossReferenceSearchService  extends VendorHttpSearchEngine {

    public MurataHttpCrossReferenceSearchService(
            LlmHelper llmHelper,
            VendorConfigFactory factory,
            @Qualifier("scraperObjectMapper") ObjectMapper mapper,
            WebClient.Builder builder
    ) {
        super(llmHelper, factory.forVendor("murata"), mapper, builder);
    }

    @Override
    public Map<String, List<Map<String, Object>>> searchByCrossReference(String competitorMpn,
                                                           List<String> categories) {

        String cleaned = competitorMpn.replace("#", "").trim();
        String cate   = categories.isEmpty()?cateForCrossRef(competitorMpn):categories.getFirst();
/*
        URI uri = UriComponentsBuilder
                .fromUriString(props.require("murata").baseUrl())
                .path(props.require("murata").crossRefPath())
                .queryParam("cate",   cate)
                .queryParam("partno", prefix)
                .build()
                .toUri();*/

//        log.debug("Murata X‑Ref GET {}", uri);

        String body = getClient().get()
                .uri(builder -> buildUri(builder, cate, cleaned))   // ← lambda
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));
        if (StringUtils.isBlank(body)) {
            log.warn("Empty response for {}", cleaned);
            return Collections.emptyMap();
        }

        return parseCrossRefJson(body);
    }

    private java.net.URI buildUri(UriBuilder ub, Object... args) {
        return ub.path(getCfg().getCrossRefUrl())
                .queryParam("cate", args[0])
                .queryParam("partno", args[1])
                .queryParam("stype", "1")
                .queryParam("lang", "en-us")
                .build();
    }


}
