package com.components.scraper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The main entry point for the Agentic Scraper application.
 # MPN Search
 python main.py mpn GRM0115C1C100GE01 --visible --output results_mpn.json

 # Parametric Search
 python main.py parametric
 --category "Capacitors"
 --subcategory "Ceramic Capacitors(SMD)"
 --parameters "{\"Capacitance\": {\"min\": 1, \"max\": 1.1}}"
 --max-results 10
 --visible
 --output results_parametric.json
 --api-key $OPENAI_API_KEY

 # Cross-Reference Search
 python main.py xref A700D107M004ATE018
 --category-path "[\"Capacitors\", \"Polymer Aluminium Electrolytic Capacitors\"]"
 --visible
 --output results_xref.json
 --api-key $OPENAI_API_KEY

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
