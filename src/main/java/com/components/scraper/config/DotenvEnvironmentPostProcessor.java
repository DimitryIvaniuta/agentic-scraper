package com.components.scraper.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * <h2>.env → Spring Environment Post‐Processor</h2>
 *
 * <p>
 * Loads environment variables defined in a {@code .env} file at application startup
 * and injects them with highest precedence into the Spring {@link ConfigurableEnvironment}.
 * This allows sensitive or per‐developer configuration (such as API keys) to be kept
 * out of version control in a simple key=value file while still being available
 * via {@code @Value} or {@code @ConfigurationProperties} in Spring beans.
 * </p>
 *
 * <p>
 * By implementing {@link EnvironmentPostProcessor} and ordering at
 * {@link Ordered#HIGHEST_PRECEDENCE}, this processor ensures that values from
 * {@code .env} override any other property sources, including application.yml,
 * system environment variables, and JVM system properties.
 * </p>
 */
public class DotenvEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    /**
     * Ensures this post‐processor runs before all others, so that
     * {@code .env} values are available immediately and take precedence.
     *
     * @return the highest precedence order
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Reads the {@code .env} file (if present) from the application root,
     * converts its entries into a {@link MapPropertySource}, and injects it
     * at the front of the {@link ConfigurableEnvironment}'s property sources.
     *
     * @param env          the current Spring {@link ConfigurableEnvironment}
     * @param application  the current {@link SpringApplication} instance
     */
    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment env,
                                       final SpringApplication application) {
        Dotenv dotenv = Dotenv.configure()
                .filename(".env")
                .ignoreIfMissing()
                .load();

        Map<String, Object> map = new HashMap<>();
        dotenv.entries().forEach(e -> map.put(e.getKey(), e.getValue()));

        // Insert at the very front so .env wins over application.yml, system properties, etc.
        env.getPropertySources()
                .addFirst(new MapPropertySource("dotenvProperties", map));
    }
}
