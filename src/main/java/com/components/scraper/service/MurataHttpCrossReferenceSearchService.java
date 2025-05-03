package com.components.scraper.service;

import com.components.scraper.config.VendorConfigFactory;
import com.components.scraper.service.core.LlmHelper;
import com.components.scraper.service.core.VendorHttpSearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
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
    public List<Map<String,Object>> searchByCrossReference(String competitorMpn,
                                                           List<String> unused) {

        String cate   = cateForCrossRef(competitorMpn);


        URI uri = UriComponentsBuilder
                .fromUriString(props.require("murata").baseUrl())
                .path(props.require("murata").crossRefPath())
                .queryParam("cate",   cate)
                .queryParam("partno", prefix)
                .build()
                .toUri();

        log.debug("Murata X‑Ref GET {}", uri);

        String json = wc.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));   // simple blocking call

        return parseJson(json);
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
