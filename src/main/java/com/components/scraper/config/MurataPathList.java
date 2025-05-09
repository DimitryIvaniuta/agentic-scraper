package com.components.scraper.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

/**
 * <h2>{@code MurataPathList}</h2>
 *
 * <p>Spring-Boot {@link ConfigurationProperties @ConfigurationProperties} bean that
 * materialises the contents of {@code murata-categories.yml} (see
 * {@code src/main/resources/murata-categories.yml}).
 * The YAML is loaded at bootstrap time and bound **once** to an immutable
 * {@link List} of {@link PathMapping} records.</p>
 *
 * <p>Because the YAML uses a list rather than a map with exotic keys,
 * IDE-assist and Spring’s configuration metadata remain free of “unknown
 * property” warnings.</p>
 */
@Configuration
@ConfigurationProperties(prefix = "murata")
@Data
public class MurataPathList {

    // ---------------------------------------------------------------------
    //  Private state
    // ---------------------------------------------------------------------

    /**
     * All <em>path-to-cate</em> mappings, exactly as declared in the YAML.
     * <p>
     * The list is injected by Spring during the binding phase; it is therefore
     * effectively immutable at runtime.
     */
    private List<PathMapping> pathToCate = Collections.emptyList();

    /**
     * One YAML row ( <strong>path</strong> → <strong>cate</strong> ).
     */
    @Data
    @NoArgsConstructor
    public static class PathMapping {

        /**
         * Human-readable <code>Category/Sub-category</code> path exactly as on the website.
         */
        private String path;

        /**
         * Exact <code>cate</code> query parameter value required by Murata Web-API.
         */
        private String cate;
    }

}
