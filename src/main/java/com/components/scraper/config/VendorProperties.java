package com.components.scraper.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps all <vendors.*> entries from application.yml into a
 * <code>Map&lt;String, VendorCfg&gt;</code>.
 */
@Component
@ConfigurationProperties(prefix = "vendors")
@Getter @Setter
public class VendorProperties {

    private final Map<String, VendorCfg> configs  = new LinkedHashMap<>();

    public Map<String, VendorCfg> getMap() { return configs; }

    public VendorCfg forName(String name) { return configs.get(name); }

}
