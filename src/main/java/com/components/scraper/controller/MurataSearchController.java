package com.components.scraper.controller;

import com.components.scraper.service.MurataHttpCrossReferenceSearchService;
import com.components.scraper.service.MurataHttpMpnSearchService;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import org.springframework.validation.annotation.Validated;

/**
 * REST end-point that exposes the <b>MPN search</b> capability.
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * POST /api/search/mpn
 * {
 *   "mpn" : "GRM0115C1C100GE01"
 * }
 * }</pre>
 *
 * <p>JSON response – each list element contains the MPN, the Murata
 * product URL and a <i>specifications</i> map keyed by the column
 * headers returned by the web page.</p>
 */
@Validated
@RestController
@RequestMapping("/api/search")
@AllArgsConstructor
public class MurataSearchController {

    MurataHttpMpnSearchService murataHttpMpnSearchService;

    /**
     * Executes an MPN search using the underlying {@link MurataHttpMpnSearchService}.
     *
     * @param request JSON body with a single {@code mpn} field
     * @return 200 OK + list-of-maps JSON
     */
    @PostMapping("/mpn")
    public ResponseEntity<List<Map<String, Object>>> searchByMpn(
            @RequestBody @Validated MpnRequest request
    ) {
//        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> results =
                murataHttpMpnSearchService.searchByMpn(request.mpn());
        return ResponseEntity.ok(results);
    }

    private final MurataHttpCrossReferenceSearchService service;

    /**
     * Search for vendor equivalents of a competitor's part number.
     *
     * @param xRefRequest  optional category navigation path segments
     * @return 200 OK, list of equivalent components
     */
    @PostMapping("/xref")
    public ResponseEntity<Map<String, List<Map<String, Object>>>> searchByCrossReference(
            @RequestBody @Validated XRefRequest xRefRequest
    ) {
        Map<String, List<Map<String, Object>>> results = service.searchByCrossReference(
                xRefRequest.competitorMpn(), xRefRequest.categoryPath()
        );
        return ResponseEntity.ok(results);
    }

    /**
     * Immutable mpn search request body wrapper.
     */
    public record MpnRequest(@NotBlank String mpn) {}

    /**
     * Immutable Cross-Ref search request body wrapper.
     */
    public record XRefRequest(@NotBlank String competitorMpn, List<String> categoryPath) {}

}
