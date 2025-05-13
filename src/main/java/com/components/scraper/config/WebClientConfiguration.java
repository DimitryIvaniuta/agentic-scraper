package com.components.scraper.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.logging.LogLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

import java.time.Duration;

@Configuration
@Slf4j
public class WebClientConfiguration {

    private static final Duration CONNECT_TIMEOUT  = Duration.ofSeconds(20);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);

    @Bean
    public WebClient.Builder webClientBuilder(@Qualifier("scraperObjectMapper") final ObjectMapper mapper) {

        /* --- JSON codecs wired to the custom ObjectMapper ------------------ */
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(cfg -> {
                    cfg.defaultCodecs()
                            .jackson2JsonEncoder(new Jackson2JsonEncoder(mapper, MediaType.APPLICATION_JSON));
                    cfg.defaultCodecs()
                            .jackson2JsonDecoder(new Jackson2JsonDecoder(mapper, MediaType.APPLICATION_JSON));
                })
                .build();

        ConnectionProvider pool = ConnectionProvider.builder("api-pool")
                .maxConnections(50)
                .pendingAcquireTimeout(Duration.ofMillis(2000))
                .build();

        HttpClient tcpClient = HttpClient.create(pool)
                .protocol(HttpProtocol.H2, HttpProtocol.HTTP11)  // try HTTP/2 multiplexing
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .responseTimeout(RESPONSE_TIMEOUT)
                .wiretap("reactor.netty.http.client.HttpClient",
                        LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL);

        return WebClient.builder()
                // each service supplies its own absolute URI → no baseUrl needed
                .clientConnector(new ReactorClientHttpConnector(tcpClient))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .filter(logRequest())
                .filter(logResponse())
                .exchangeStrategies(strategies);
    }

    private static ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            log.debug("--> {} {}  {}", req.method(), req.url(), req.headers());
            return Mono.just(req);
        });
    }

    private static ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(res -> {
            log.debug("<-- {}  {}", res.statusCode().value(), res.headers().asHttpHeaders());
            return Mono.just(res);
        });
    }
}
