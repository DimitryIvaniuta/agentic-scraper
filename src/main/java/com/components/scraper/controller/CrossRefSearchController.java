package com.components.scraper.controller;

import com.components.scraper.dto.CrossRefRequest;
import com.components.scraper.service.core.CrossReferenceSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search/cross-ref")
@RequiredArgsConstructor
public class CrossRefSearchController {

    private final Map<String, CrossReferenceSearchService> crossRefServices;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> searchByCrossRef(
            @RequestParam("vendor")  String vendor,
            @RequestBody @Validated CrossRefRequest crossRefRequest) {

        String competitorPart = crossRefRequest.competitorMpn();
        CrossReferenceSearchService svc = crossRefServices.get(vendor + "CrossRefSvc");
        if (svc == null){
            throw new IllegalArgumentException("No X‑ref service for vendor " + vendor);

        }

        return ResponseEntity.ok(svc.searchByCrossReference(competitorPart, crossRefRequest.categoryPath()));
    }
}