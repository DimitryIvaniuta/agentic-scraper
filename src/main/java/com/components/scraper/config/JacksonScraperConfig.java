package com.components.scraper.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonScraperConfig {

    /**
     * A dedicated {@link ObjectMapper} for our scraping layer.
     * <p>
     * • Has its own qualifier (<b>scraperObjectMapper</b>) so it never clashes with the
     * default mapper that Spring Boot auto‑configures for MVC.<br>
     * • You can customise modules / features here without affecting the rest of
     * the application.
     *
     * @return ObjectMapper for scraper
     */
    @Bean
    @Qualifier("scraperObjectMapper")          // the qualifier Spring will look for
    public ObjectMapper scraperObjectMapper() {

        ObjectMapper mapper = new ObjectMapper();

        // Whatever fine‑tuning you need for scraped JSON:
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return mapper;
    }

}
