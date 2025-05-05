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
 * Loads a `.env` file from the application root and adds its entries
 * as a high-priority property source so that
 * `${OPENAI_API_KEY}` and friends resolve correctly.
 */
public class DotenvEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    /** highest precedence so .env entries override everything else */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env,
                                       SpringApplication application) {
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
