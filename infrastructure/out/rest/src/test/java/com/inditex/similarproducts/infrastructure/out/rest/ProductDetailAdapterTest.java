package com.inditex.similarproducts.infrastructure.out.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.inditex.similarproducts.model.ProductDetail;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.test.StepVerifier;

class ProductDetailAdapterTest {

    private WireMockServer wireMockServer;
    private ProductDetailAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();

        WebClient webClient = WebClient.create("http://localhost:" + wireMockServer.port());
        TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(500)).build());
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom().slidingWindowSize(20).minimumNumberOfCalls(10).build());
        MocksProperties properties = new MocksProperties(
                "http://localhost:" + wireMockServer.port(),
                500,
                new MocksProperties.ProductDetailCache(Duration.ofSeconds(30), 10_000));

        adapter = new ProductDetailAdapter(webClient, timeLimiterRegistry, circuitBreakerRegistry, properties);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void returnsProductDetailOnSuccess() {
        wireMockServer.stubFor(get(urlEqualTo("/product/1"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}")));

        StepVerifier.create(adapter.findProductDetail("1"))
                .expectNext(new ProductDetail("1", "Shirt", BigDecimal.valueOf(9.99), true))
                .verifyComplete();
    }

    @Test
    void propagatesErrorOnNotFound() {
        wireMockServer.stubFor(get(urlEqualTo("/product/5"))
                .willReturn(aResponse().withStatus(404)));

        StepVerifier.create(adapter.findProductDetail("5"))
                .expectError(WebClientResponseException.NotFound.class)
                .verify();
    }

    @Test
    void secondCallForSameIdIsServedFromCache() {
        wireMockServer.stubFor(get(urlEqualTo("/product/1"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}")));

        adapter.findProductDetail("1").block();
        adapter.findProductDetail("1").block();

        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/product/1")));
    }

    @Test
    void failedCallIsNotCached() {
        wireMockServer.stubFor(get(urlEqualTo("/product/6"))
                .willReturn(aResponse().withStatus(500)));

        StepVerifier.create(adapter.findProductDetail("6")).expectError().verify();
        StepVerifier.create(adapter.findProductDetail("6")).expectError().verify();

        wireMockServer.verify(2, getRequestedFor(urlEqualTo("/product/6")));
    }

    @Test
    void circuitBreakerOpensAfterRepeatedFailures() {
        wireMockServer.stubFor(get(urlEqualTo("/product/7"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 10; i++) {
            StepVerifier.create(adapter.findProductDetail("7")).expectError().verify();
        }

        StepVerifier.create(adapter.findProductDetail("7"))
                .expectError(CallNotPermittedException.class)
                .verify();

        wireMockServer.verify(10, getRequestedFor(urlEqualTo("/product/7")));
    }

    @Test
    void propagatesErrorWhenCallTimesOut() {
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ProductDetailAdapter.class);
        logAppender.start();
        logger.addAppender(logAppender);

        wireMockServer.stubFor(get(urlEqualTo("/product/9"))
                .willReturn(aResponse()
                        .withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"9\",\"name\":\"Coat\",\"price\":59.99,\"availability\":true}")));

        StepVerifier.create(adapter.findProductDetail("9"))
                .expectError(TimeoutException.class)
                .verify();

        boolean timeoutLogged = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("timed out"));
        assertThat(timeoutLogged).isTrue();

        logger.detachAppender(logAppender);
    }

    @Test
    void logsCircuitBreakerStateTransitionWhenItOpens() {
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ProductDetailAdapter.class);
        logAppender.start();
        logger.addAppender(logAppender);

        wireMockServer.stubFor(get(urlEqualTo("/product/10"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 10; i++) {
            StepVerifier.create(adapter.findProductDetail("10")).expectError().verify();
        }

        boolean transitionLogged = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.INFO
                        && event.getFormattedMessage().contains("CLOSED to OPEN"));
        assertThat(transitionLogged).isTrue();

        logger.detachAppender(logAppender);
    }
}
