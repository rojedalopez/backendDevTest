package com.inditex.similarproducts.infrastructure.out.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.inditex.similarproducts.model.ProductNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class SimilarProductIdsAdapterTest {

    private WireMockServer wireMockServer;
    private SimilarProductIdsAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();

        WebClient webClient = WebClient.create("http://localhost:" + wireMockServer.port());
        TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(500)).build());
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom().slidingWindowSize(20).minimumNumberOfCalls(10).build());

        adapter = new SimilarProductIdsAdapter(webClient, timeLimiterRegistry, circuitBreakerRegistry);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void returnsSimilarIdsOnSuccess() {
        wireMockServer.stubFor(get(urlEqualTo("/product/1/similarids"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[\"2\",\"3\"]")));

        StepVerifier.create(adapter.findSimilarProductIds("1"))
                .expectNext(List.of("2", "3"))
                .verifyComplete();
    }

    @Test
    void mapsNotFoundToProductNotFoundException() {
        wireMockServer.stubFor(get(urlEqualTo("/product/5/similarids"))
                .willReturn(aResponse().withStatus(404)));

        StepVerifier.create(adapter.findSimilarProductIds("5"))
                .expectError(ProductNotFoundException.class)
                .verify();
    }

    @Test
    void propagatesErrorWhenCallTimesOut() {
        wireMockServer.stubFor(get(urlEqualTo("/product/1/similarids"))
                .willReturn(aResponse()
                        .withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[\"2\"]")));

        StepVerifier.create(adapter.findSimilarProductIds("1"))
                .expectError(TimeoutException.class)
                .verify();
    }

    @Test
    void circuitBreakerOpensAfterRepeatedFailures() {
        wireMockServer.stubFor(get(urlEqualTo("/product/6/similarids"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 10; i++) {
            StepVerifier.create(adapter.findSimilarProductIds("6")).expectError().verify();
        }

        StepVerifier.create(adapter.findSimilarProductIds("6"))
                .expectError(CallNotPermittedException.class)
                .verify();

        wireMockServer.verify(10, getRequestedFor(urlEqualTo("/product/6/similarids")));
    }
}
