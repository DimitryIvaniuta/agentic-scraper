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

/**
 * REST controller that exposes an endpoint for performing cross-reference lookups
 * against various component vendors.
 * <p>
 * Clients must supply a {@code vendor} query parameter to select which vendor's
 * cross-reference service to invoke, and a JSON body containing the competitor
 * part number (MPN) and an optional category path.
 * </p>
 * <p>
 * Example request:
 * <pre>{@code
 * POST /api/search/cross-ref?vendor=murata
 * Content-Type: application/json
 *
 * {
 *   "competitorMpn": "XAL4020152ME",
 *   "categoryPath": ["Inductors", "Power Inductors"]
 * }
 * }</pre>
 * </p>
 */
@RestController
@RequestMapping("/api/search/cross-ref")
@RequiredArgsConstructor
public class CrossRefSearchController {

    /**
     * Map of vendor identifiers (with suffix “CrossRefSvc”) to their
     * {@link CrossReferenceSearchService} implementations.
     */
    private final Map<String, CrossReferenceSearchService> crossRefServices;

    /**
     * Perform a cross-reference search for a competitor part number against
     * the specified vendor's catalog.
     *
     * @param vendor          the vendor identifier (e.g., "murata", "tdk")
     * @param crossRefRequest the request body containing:
     *                        <ul>
     *                          <li>{@code competitorMpn}: the competitor part number</li>
     *                          <li>{@code categoryPath}: optional list of categories or subcategories</li>
     *                        </ul>
     * @return HTTP 200 with a JSON array of maps, each mapping field names to values,
     * representing matched cross-reference entries
     * @throws IllegalArgumentException if no service is registered for the given vendor
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> searchByCrossRef(
            @RequestParam("vendor") final String vendor,
            @RequestBody @Validated final CrossRefRequest crossRefRequest) {

        String competitorPart = crossRefRequest.competitorMpn();
        CrossReferenceSearchService svc = crossRefServices.get(vendor + "CrossRefSvc");
        if (svc == null) {
            throw new IllegalArgumentException("No X‑ref service for vendor " + vendor);

        }

        return ResponseEntity.ok(svc.searchByCrossReference(competitorPart, crossRefRequest.categoryPath()));
    }
}
