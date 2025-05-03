package com.components.scraper.config;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds and validates OpenAI-related configuration.
 * <p>
 * You can set the API key in <code>application.yml</code> under
 * <code>openai.api-key</code>, or supply an environment variable
 * <code>OPENAI_API_KEY</code> (Spring will bind it automatically).
 * </p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "openai")
public class OpenAIProperties {

    /**
     * API key for the LLM service.
     * <p>
     * Example in <code>application.yml</code>:
     * <pre>
     * openai:
     *   api-key: your-key-here
     * </pre>
     * Or alternatively:
     * <pre>
     * export OPENAI_API_KEY=your-key-here
     * </pre>
     */
    @Size(min = 1, message = "openai.api-key must not be empty if provided")
    @Pattern(regexp = "^[A-Za-z0-9\\-_.]+$", message = "openai.api-key contains invalid characters")
    private String apiKey;

}
