package com.components.scraper.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds vendor-specific configuration from <code>application.yml</code>
 * under the <code>vendors</code> prefix. Each entry in the bound map
 * corresponds to a {@link VendorCfg} object keyed by the vendor identifier.
 * <p>
 * Example YAML:
 * <pre>{@code
 * vendors:
 *   murata:
 *     base-url: https://www.murata.com
 *     mpn-search-path: /webapi/PsdispRest
 *     # ...
 *   tdk:
 *     base-url: https://product.tdk.com
 *     # ...
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "vendors")
@Getter
@Setter
public class VendorProperties {

    /**
     * Map of vendor identifiers to their corresponding {@link VendorCfg}
     * instances, preserving insertion order.
     */
    private final Map<String, VendorCfg> configs = new LinkedHashMap<>();

    private Integer maxPageSize;

    /**
     * Alias for {@link #configs} for clearer naming in code.
     *
     * @return the map of vendor configurations
     */
    public Map<String, VendorCfg> getMap() {
        return configs;
    }

    /**
     * Retrieves the {@link VendorCfg} for the given vendor name.
     *
     * @param name the vendor identifier
     * @return the {@link VendorCfg} associated with {@code name}, or {@code null}
     * if no such vendor is configured
     */
    public VendorCfg forName(final String name) {
        return configs.get(name);
    }

}
