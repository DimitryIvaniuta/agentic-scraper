package com.components.scraper.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds the external <code>parametric-filters.yml</code> into a
 * structured, type-safe bean.
 *
 * <p>Example structure:</p>
 * <pre>
 * parametric-filters:
 *   murata:
 *     categories:
 *       luCeramicCapacitorsSMD:
 *         filters:
 *           - caption: "Capacitance"
 *             param: ceramicCapacitors-capacitance
 *             type: range
 *           …
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "parametric-filters")
public class ParametricFilterConfig {

    /**
     * All vendor configurations by vendor-key, e.g. "murata".
     */
    private Map<String, VendorFilters> configs = new LinkedHashMap<>();

    /**
     * Lookup the filters for a given vendor and category code.
     *
     * @param vendorKey  e.g. "murata"
     * @param cate       the Murata cate code, e.g. "luCeramicCapacitorsSMD"
     * @return the matching {@link CategoryFilters}, or {@code null} if none found
     */
    public CategoryFilters getCategoryFilters(String vendorKey, String cate) {
        VendorFilters vf = configs.get(vendorKey);
        return (vf != null ? vf.getCategories().get(cate) : null);
    }

    /**
     * Top-level holder for a single vendor's filter definitions.
     */
    @Data
    public static class VendorFilters {
        /** Map from cate code → its filters. */
        private Map<String, CategoryFilters> categories = new LinkedHashMap<>();
    }

    /**
     * All of the named filters for one Murata category.
     */
    @Data
    public static class CategoryFilters {
        /** One entry per UI filter. */
        private List<FilterDef> filters;
    }

    /**
     * Defines a single parametric filter:
     *  - the human caption shown in the UI,
     *  - the Murata API field name (<code>scon</code> key),
     *  - and the type (range vs multi vs literal).
     */
    @Data
    public static class FilterDef {
        /** Exactly matches the UI label, e.g. "Capacitance" */
        private String caption;
        /** The Murata API parameter, e.g. "ceramicCapacitors-capacitance" */
        private String param;
        /** "range", "multi", or "literal" */
        private String type;
    }
}
