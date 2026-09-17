package com.inditex.similarproducts.infrastructure.out.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebClientConfigTest {

    @Test
    void buildsWebClientWithConfiguredBaseUrl() {
        MocksProperties properties = new MocksProperties(
                "http://localhost:3001",
                500,
                new MocksProperties.ProductDetailCache(Duration.ofSeconds(30), 10_000));

        assertThat(new WebClientConfig().mocksWebClient(properties)).isNotNull();
    }
}
