package com.components.scraper.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * One‑liner helper that hands the right config object to every provider
 * without polluting service constructors with the full {@link VendorProperties}.
 */
@Component
@RequiredArgsConstructor
public class VendorConfigFactory {

    @Autowired
    private VendorProperties vendorProps;

    /**
     * @param id vendor‑id from YAML (“murata”, “tdk”, …)
     * @return immutable view of the configuration for that vendor
     * @throws IllegalArgumentException if no such vendor is configured
     */
    public VendorCfg forVendor(String id) {
        return Optional.ofNullable(vendorProps.forName(id))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No <vendors." + id + "> section found in application.yml"));
    }

}
