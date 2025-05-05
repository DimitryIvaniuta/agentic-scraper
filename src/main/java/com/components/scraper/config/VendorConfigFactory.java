package com.components.scraper.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Factory component responsible for producing {@link VendorCfg} instances
 * for a given vendor identifier. It delegates to {@link VendorProperties}
 * to look up the configuration section for each vendor as defined in
 * <code>application.yml</code>.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * @Autowired
 * private VendorConfigFactory configFactory;
 *
 * VendorCfg murataCfg = configFactory.forVendor("murata");
 * }</pre>
 */
@Component
@RequiredArgsConstructor
public class VendorConfigFactory {

    /**
     * Aggregated vendor-specific configuration loaded from
     * <code>application.yml</code> under the <code>vendors</code> namespace.
     */
    @Autowired
    private VendorProperties vendorProps;

    /**
     * Retrieves the {@link VendorCfg} for the specified vendor ID.
     *
     * @param id the vendor identifier (must match a key under
     *           <code>vendors.{id}</code> in application.yml)
     * @return the corresponding {@link VendorCfg} instance
     * @throws IllegalArgumentException if no configuration section is found
     *                                  for the given vendor ID
     */
    public VendorCfg forVendor(final String id) {
        return Optional.ofNullable(vendorProps.forName(id))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No <vendors." + id + "> section found in application.yml"));
    }

}
