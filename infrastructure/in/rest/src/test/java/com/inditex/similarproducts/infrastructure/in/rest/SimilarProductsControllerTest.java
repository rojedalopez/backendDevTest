package com.inditex.similarproducts.infrastructure.in.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.model.ProductNotFoundException;
import com.inditex.similarproducts.usecase.GetSimilarProductsUseCase;
import com.inditex.similarproducts.usecase.SimilarProductsResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class SimilarProductsControllerTest {

    private final GetSimilarProductsUseCase useCase = mock(GetSimilarProductsUseCase.class);
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new SimilarProductsController(useCase))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsOkWithCompleteResult() {
        ProductDetail detail = new ProductDetail("2", "Dress", BigDecimal.valueOf(19.99), true);
        when(useCase.getSimilarProducts("1"))
                .thenReturn(Mono.just(new SimilarProductsResult(List.of(detail), false)));

        webTestClient.get().uri("/product/1/similar")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductDetail.class).contains(detail);
    }

    @Test
    void returnsPartialContentWhenResultIsPartial() {
        when(useCase.getSimilarProducts("4"))
                .thenReturn(Mono.just(new SimilarProductsResult(List.of(), true)));

        webTestClient.get().uri("/product/4/similar")
                .exchange()
                .expectStatus().isEqualTo(206)
                .expectBody().jsonPath("$").isArray();
    }

    @Test
    void returnsNotFoundWhenBaseProductMissing() {
        when(useCase.getSimilarProducts("404")).thenReturn(Mono.error(new ProductNotFoundException("404")));

        webTestClient.get().uri("/product/404/similar")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("Product not found: 404");
    }

    @Test
    void returnsServerErrorOnUnexpectedFailure() {
        when(useCase.getSimilarProducts("1")).thenReturn(Mono.error(new RuntimeException("boom")));

        webTestClient.get().uri("/product/1/similar")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(500)
                .jsonPath("$.detail").isEqualTo("An unexpected error occurred.");
    }
}
