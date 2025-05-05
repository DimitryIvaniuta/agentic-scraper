package com.components.scraper.config;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for OpenAI integration.
 *
 * <p>Values are bound from properties prefixed with {@code openai} in your
 * Spring environment (e.g. application.yml or .env via Spring Boot's dotenv support).</p>
 *
 * <p>Example application.yml snippet:
 * <pre>
 * openai:
 *   api-key: ${OPENAI_API_KEY}
 *   base-url: <a href="https://api.openai.com/v1" >https://api.openai.com/v1</a>
 *   default-model: gpt-3.5-turbo
 * </pre>
 * </p>
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "openai.api")
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
    @NotBlank(message = "openai.api.key must be set")
    private String key;

    /** The base URL for OpenAI API calls. */
    @NotBlank
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * The default model to use when calling chat/completions.
     * You can override this in your YAML or .env.
     */
    @NotBlank
    private String defaultModel = "gpt-3.5-turbo";

}
