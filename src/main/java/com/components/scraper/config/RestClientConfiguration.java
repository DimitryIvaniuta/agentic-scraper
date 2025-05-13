package com.components.scraper.config;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration for creating a pre-configured {@link RestClient} bean
 * that all scrapper services will use to perform HTTP requests.
 * <p>
 * This configuration customizes the base RestClientBuilder by:
 * <ul>
 *   <li>Leaving the base URL blank (individual services supply their own absolute URIs).</li>
 *   <li>Setting a default "Accept: application/json" header.</li>
 *   <li>Replacing the default Jackson message converterGÇÖs ObjectMapper
 *       with the scraper-specific one (qualified "scraperObjectMapper").</li>
 * </ul>
 */
@Configuration
public class RestClientConfiguration {

    /**
     * Defines a {@link RestClient} bean to be injected into all scraping services.
     * <p>
     * The provided {@code RestClient.Builder} is used so that the application
     * can centrally configure headers and JSON mapping behavior, while each
     * service method still supplies its own request URI.
     *
     * @param builder a {@link RestClient.Builder} provided by Spring
     * @param om      the scraper-specific {@link ObjectMapper} to use for JSON
     *                deserialization (qualified as "scraperObjectMapper")
     * @return a fully built {@link RestClient} instance
     */
    @Bean
    public RestClient restClient(final RestClient.Builder builder,
                                 @Qualifier("scraperObjectMapper") final ObjectMapper om) {
        return builder
                .baseUrl("")          // every service sets its own absolute URI
                .defaultHeader("Accept", "application/json")
                .messageConverters(converters -> converters
                        .stream()
                        .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                        .findFirst()
                        .ifPresent(c -> ((MappingJackson2HttpMessageConverter) c)
                                .setObjectMapper(om)))
                .build();
    }

}
