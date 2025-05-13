package com.components.scraper.service.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry that wires all vendor services.
 * Central registry – inject this where you need a vendor‑specific search instance.
 */
@Component
@Slf4j
class SearchServiceRegistry {

    private final Map<String, MpnSearchService> byVendor;

    @Autowired
    SearchServiceRegistry(List<MpnSearchService> services) {
        // Map key is upper‑cased vendor to make look‑ups case‑insensitive.
        this.byVendor = services.stream()
                .collect(Collectors.toUnmodifiableMap(s -> s.vendor().toUpperCase(Locale.ROOT),
                        Function.identity()));
        log.info("Registered MPN search services: {}", byVendor.keySet());
    }

    public Optional<MpnSearchService> find(String vendor) {
        return Optional.ofNullable(byVendor.get(vendor.toUpperCase(Locale.ROOT)));
    }

    public Set<String> supportedVendors() {
        return byVendor.keySet();
    }
}
