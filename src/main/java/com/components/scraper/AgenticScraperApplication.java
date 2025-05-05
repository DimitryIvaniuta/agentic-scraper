package com.components.scraper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The main entry point for the Agentic Scraper application.
 *
 * <p>This Spring Boot application exposes RESTful endpoints for:
 * <ul>
 *   <li>Manufacturer Part Number (MPN) search,</li>
 *   <li>Cross-reference lookup,</li>
 *   <li>Parametric component search.</li>
 * </ul>
 * It wires together vendor-specific HTTP services, JSON/GID parsers, and
 * Spring MVC controllers to provide a unified scraping API.</p>
 *
 * <p>Usage:
 * <pre>{@code
 *   // From the command line:
 *   mvn spring-boot:run
 *
 *   // Or run the JAR:
 *   java -jar target/AgenticScraperApplication.jar
 * }</pre>
 *
 * <p>Once started, the application will listen on the configured port (default
 * 8080) and serve requests under <code>/api/search/</code>.</p>
 */
@SpringBootApplication
public class AgenticScraperApplication {

    /**
     * Bootstrap method to launch the Spring Boot application.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(final String[] args) {
        SpringApplication.run(AgenticScraperApplication.class, args);
    }
}
