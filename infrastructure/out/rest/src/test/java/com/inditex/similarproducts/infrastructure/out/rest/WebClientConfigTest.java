package com.inditex.similarproducts.infrastructure.out.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class WebClientConfigTest {

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void buildsWebClientTargetingTheConfiguredBaseUrl() {
        ProductCatalogProperties properties = new ProductCatalogProperties(
                "http://localhost:" + wireMockServer.port(),
                500,
                new ProductCatalogProperties.ProductDetailCache(Duration.ofSeconds(30), 10_000));

        wireMockServer.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200)));

        WebClient webClient = new WebClientConfig().productCatalogWebClient(properties);

        StepVerifier.create(webClient.get().uri("/ping").retrieve().toBodilessEntity())
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();
    }
}
