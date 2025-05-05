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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * REST controller exposing an endpoint for parametric product searches.
 * <p>
 * Allows clients to specify a vendor, category, optional subcategory,
 * and a set of filter parameters to retrieve matching products.
 * </p>
 * <p>
 * Endpoint: <code>POST /api/search/parametric?vendor={vendor}</code><br>
 * Consumes: <code>application/json</code><br>
 * Produces: <code>application/json</code>
 * </p>
 *
 * <h3>Request Parameters</h3>
 * <ul>
 *   <li><strong>vendor</strong> (query parameter, required): the vendor identifier (e.g., "murata", "tdk")</li>
 * </ul>
 *
 * <h3>Request Body</h3>
 * A JSON object matching {@link ParametricSearchRequest}, for example:
 * <pre>{@code
 * {
 *   "category": "Capacitors",
 *   "subcategory": "Ceramic",
 *   "parameters": {
 *     "capacitance": {"min": 10, "max": 100},
 *     "ratedVoltageDC": [16, 25]
 *   },
 *   "maxResults": 50
 * }
 * }</pre>
 *
 * <h3>Response</h3>
 * A JSON array of maps, each representing a product with its attribute–value pairs.
 *
 * <h3>Error Handling</h3>
 * <ul>
 *   <li>400 BAD REQUEST: Invalid vendor or missing/invalid request body</li>
 * </ul>
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
     * "tdkHttpParametricSearchService"    ⇒ bean instance
     */
    private final Map<String, ParametricSearchService> parametricServices;

    /**
     * Executes a parametric search based on the provided category, subcategory, and filter parameters.
     *
     * @param vendor the vendor identifier (e.g., "murata", "tdk"); defaults to "murata" if blank
     * @param dto    the request payload containing:
     *               <ul>
     *                 <li>{@code category} (required)</li>
     *                 <li>{@code subcategory} (optional)</li>
     *                 <li>{@code parameters}: map of filter names to values, ranges, or lists</li>
     *                 <li>{@code maxResults}: maximum number of rows to return (optional)</li>
     *               </ul>
     * @return a {@link ResponseEntity} with HTTP 200 and a JSON array of matching product maps,
     *         or HTTP 400 if the vendor is not supported
     */
    @PostMapping
    public ResponseEntity<List<Map<String, Object>>> searchByParameters(
            @RequestParam("vendor") final String vendor,
            @Valid @RequestBody final ParametricSearchRequest dto) {
        String vendorKey = resolveVendorKey(vendor);
        ParametricSearchService svc = Optional
                .ofNullable(parametricServices.get(vendorKey))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No parametric search service for vendor: " + vendor));

        List<Map<String, Object>> rows = svc.searchByParameters(
                dto.getCategory(),
                dto.getSubcategory(),
                dto.getParameters(),
                dto.getMaxResultsOrDefault());

        return ResponseEntity.ok(rows);
    }

    /**
     * Normalizes the vendor string and appends the expected service bean suffix.
     *
     * @param vendor the raw vendor identifier from the request
     * @return the bean name key, e.g. "murataParamSvc"
     */
    private String resolveVendorKey(final String vendor) {
        String v = (StringUtils.hasText(vendor) ? vendor : "murata").toLowerCase();
        return v + "ParamSvc";
    }

    /**
     * Handles invalid arguments thrown during request processing, such as unsupported vendors.
     *
     * @param ex the exception containing the error details
     * @return a {@link ResponseEntity} with HTTP 400 and a JSON body {"error": "..."}
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(final IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }
}
