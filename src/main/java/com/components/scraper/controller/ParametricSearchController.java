package com.components.scraper.controller;

import com.components.scraper.dto.ParametricSearchRequest;
import com.components.scraper.service.core.ParametricSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST entry‑point for parametric (specification) searches.
 * POST: /api/search/parametric
 * Request body:
 * <pre>
 * {
 *   "vendor"     : "murata",                 // optional – defaults to "murata"
 *   "category"   : "GRM",                    // main family / prefix
 *   "subcategory": null,                     // optional
 *   "parameters" : {                         // filter map (see service javadoc)
 *       "capacitance"       : {"min": 10, "max": 125},
 *       "rated_voltage_dc"  : 16
 *   },
 *   "maxResults" : 50                        // optional – defaults to 100
 * }
 * </pre>
 *
 * Response – 200 OK:
 * <pre>
 * [
 *   { "Part Number" : "GRM0115C1C100GE01#", "Capacitance" : "10 pF", … },
 *   { … }
 * ]
 * </pre>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/search/parametric")
@RequiredArgsConstructor
public class ParametricSearchController {

    /**
     * All beans that implement {@link ParametricSearchService} will be
     * injected here automatically; the map key equals the Spring bean‑name.
     * <p>
     * Example:  "murataHttpParametricSearchService" ⇒ bean instance
     *            "tdkHttpParametricSearchService"    ⇒ bean instance
     */
    private final Map<String, ParametricSearchService> parametricServices;

    /* --------------------------------------------------- endpoint ---- */

    @PostMapping
    public ResponseEntity<List<Map<String, Object>>> searchByParameters(@Valid @RequestBody ParametricSearchRequest dto) {

        String vendorKey = resolveVendorKey(dto.getVendor());
        ParametricSearchService svc = Optional
                .ofNullable(parametricServices.get(vendorKey))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No parametric search service for vendor: " + dto.getVendor()));

        List<Map<String, Object>> rows = svc.searchByParameters(
                dto.getCategory(),
                dto.getSubcategory(),
                dto.getParameters(),
                dto.getMaxResultsOrDefault());

        return ResponseEntity.ok(rows);
    }

    /**
     * Return the bean‑name that Spring uses for the desired vendor
     * (<code>murata</code> → <code>murataHttpParametricSearchService</code>).
     */
    private String resolveVendorKey(String vendor) {
        String v = (StringUtils.hasText(vendor) ? vendor : "murata").toLowerCase();
        return v + "HttpParametricSearchService";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }
}
