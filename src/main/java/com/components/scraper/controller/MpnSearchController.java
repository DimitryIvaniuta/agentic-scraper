package com.components.scraper.controller;

import com.components.scraper.dto.MpnRequest;
import com.components.scraper.service.core.MpnSearchService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search/mpn")
@RequiredArgsConstructor
public class MpnSearchController {

    /** injected map: key=bean‑name, value=implementation */
    private final Map<String, MpnSearchService> mpnServices;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> searchByMpn(@RequestBody @Validated MpnRequest request) {
        MpnSearchService svc = pick(request.vendor());
        return svc.searchByMpn(request.mpn());
    }

    private MpnSearchService pick(String vendor) {
        MpnSearchService service = mpnServices.get(vendor + "MpnSvc");
        if (service == null) {
            throw new IllegalArgumentException("No MPN service for vendor " + vendor);
        }
        return service;
    }

}
