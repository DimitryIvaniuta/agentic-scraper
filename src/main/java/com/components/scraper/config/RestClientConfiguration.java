package com.components.scraper.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfiguration {

    @Bean
    public RestClient restClient(RestClient.Builder builder,
                          @Qualifier("scraperObjectMapper") ObjectMapper om) {
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
