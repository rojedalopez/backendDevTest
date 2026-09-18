package com.inditex.similarproducts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = "server.port=0")
class SimilarProductsValidationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void rejectsOverlyLongProductId() {
        String tooLong = "x".repeat(65);

        webTestClient.get().uri("/product/{productId}/similar", tooLong)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isEqualTo("productId: size must be between 0 and 64");
    }

    @Test
    void rejectsNegativePage() {
        webTestClient.get().uri("/product/1/similar?page=-1")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.detail").isEqualTo("page: must be greater than or equal to 0");
    }

    @Test
    void rejectsPageExceedingMaximum() {
        webTestClient.get().uri("/product/1/similar?page=100001")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.detail").isEqualTo("page: must be less than or equal to 100000");
    }

    @Test
    void rejectsSizeBelowMinimum() {
        webTestClient.get().uri("/product/1/similar?size=0")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.detail").isEqualTo("size: must be greater than or equal to 1");
    }

    @Test
    void rejectsSizeExceedingMaximum() {
        webTestClient.get().uri("/product/1/similar?size=51")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.detail").isEqualTo("size: must be less than or equal to 50");
    }
}
