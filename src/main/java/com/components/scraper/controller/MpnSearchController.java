package com.components.scraper.controller;

import com.components.scraper.dto.MpnRequest;
import com.components.scraper.service.core.MpnSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller exposing an endpoint to search for products by Manufacturer Part Number (MPN).
 * <p>
 * Clients POST a JSON body containing the desired vendor and part number, and receive
 * a list of matching product records as JSON maps.
 * </p>
 * <p>
 * Endpoint: <code>POST /api/search/mpn</code><br>
 * Consumes: <code>application/json</code><br>
 * Produces: <code>application/json</code>
 * </p>
 *
 * <h3>Example Request</h3>
 * <pre>{@code
 * POST /api/search/mpn
 * Content-Type: application/json
 *
 * {
 *   "vendor": "murata",
 *   "mpn": "GRM0115C1C100GE01"
 * }
 * }</pre>
 *
 * <h3>Example Response</h3>
 * <pre>{@code
 * [
 *   {
 *     "mpn": "GRM0115C1C100GE01",
 *     "url": "https://www.murata.com/en-us/products/productdetail?partno=GRM0115C1C100GE01%23",
 *     "specifications": { ... }
 *   },
 *   ...
 * ]
 * }</pre>
 */
@RestController
@RequestMapping("/api/search/mpn")
@RequiredArgsConstructor
public class MpnSearchController {

    /**
     * Map of Spring-managed {@link MpnSearchService} implementations keyed by bean name.
     * Injected map: key=bean‑name, value=implementation.
     * Expected keys have the pattern "{vendor}MpnSvc".
     */
    private final Map<String, MpnSearchService> mpnServices;

    /**
     * Handles POST requests to search for a product by its MPN.
     *
     * @param request the {@link MpnRequest} containing:
     *                <ul>
     *                  <li>{@code vendor}: the vendor identifier (e.g., "murata", "tdk")</li>
     *                  <li>{@code mpn}: the manufacturer part number to look up</li>
     *                </ul>
     * @return a {@link List} of {@link Map} objects, each representing a product record
     * @throws IllegalArgumentException if no {@link MpnSearchService} is configured for the specified vendor
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> searchByMpn(@RequestBody @Validated final MpnRequest request) {
        MpnSearchService svc = pick(request.vendor());
        return svc.searchByMpn(request.mpn());
    }

    /**
     * Selects the appropriate {@link MpnSearchService} implementation based on vendor identifier.
     *
     * @param vendor the vendor key, matching the prefix of the bean name (e.g., "murata" → "murataMpnSvc")
     * @return the {@link MpnSearchService} for that vendor
     * @throws IllegalArgumentException if no service bean is found for the given vendor
     */
    private MpnSearchService pick(final String vendor) {
        MpnSearchService service = mpnServices.get(vendor + "MpnSvc");
        if (service == null) {
            throw new IllegalArgumentException("No MPN service for vendor " + vendor);
        }
        return service;
    }

}
