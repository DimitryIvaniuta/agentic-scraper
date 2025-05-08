package com.components.scraper.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <h2>Resilience4j Configuration</h2>
 *
 * <p>
 * Defines and exposes the core Resilience4j registries and resilience policies
 * used throughout the application for AI‐backed category lookups (and any other
 * future resilience needs).  By externalizing these as Spring beans, services
 * can declaratively inject and apply retries and circuit breakers without
 * having to instantiate or configure them directly.
 * </p>
 */
@Configuration
public class Resilience4jConfig {

    /**
     * Creates the global {@link RetryRegistry} which holds all configured
     * {@link Retry} instances.
     *
     * @return a registry pre‐populated with default retry configuration
     */
    @Bean
    public RetryRegistry retryRegistry() {
        return RetryRegistry.ofDefaults();
    }
    /**
     *
     * Creates the global {@link CircuitBreakerRegistry} which holds all
     * configured {@link CircuitBreaker} instances.
     *
     * @return a registry pre‐populated with default circuit‐breaker configuration
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    /**
     * Defines a named {@link Retry} policy for AI category lookups.
     * <p>
     * All calls wrapped with this retry instance will follow the default
     * retry strategy (e.g. exponential backoff, max attempts).
     * </p>
     *
     * @param registry the global {@link RetryRegistry} to pull from
     * @return a {@link Retry} configured under the name "aiCategoryLookup"
     */
    @Bean
    public Retry aiRetry(final RetryRegistry registry) {
        return registry.retry("aiCategoryLookup");
    }

    /**
     * Defines a named {@link CircuitBreaker} for AI category lookups.
     * <p>
     * When too many failures occur, this breaker will open and prevent
     * additional AI calls until the failure rate improves.
     * </p>
     *
     * @param registry the global {@link CircuitBreakerRegistry} to pull from
     * @return a {@link CircuitBreaker} configured under the name "aiCategoryLookup"
     */
    @Bean
    public CircuitBreaker aiCircuitBreaker(final CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("aiCategoryLookup");
    }

}
