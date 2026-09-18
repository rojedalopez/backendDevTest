package com.inditex.similarproducts.infrastructure.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.model.ProductNotFoundException;
import com.inditex.similarproducts.usecase.GetSimilarProductsUseCase;
import com.inditex.similarproducts.usecase.SimilarProductsResult;
import jakarta.validation.ConstraintViolationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
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

    @Test
    void logsUnexpectedExceptionsServerSide() {
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender.start();
        logger.addAppender(logAppender);

        when(useCase.getSimilarProducts("1")).thenReturn(Mono.error(new RuntimeException("boom")));

        webTestClient.get().uri("/product/1/similar")
                .exchange()
                .expectStatus().is5xxServerError();

        boolean errorLogged = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("Unhandled exception"));
        assertThat(errorLogged).isTrue();

        logger.detachAppender(logAppender);
    }

    @Test
    void mapsConstraintViolationToBadRequestProblemDetail() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ConstraintViolationException exception =
                new ConstraintViolationException("productId size must be <= 64", Set.of());

        ProblemDetail problemDetail = handler.handleValidationFailure(exception);

        assertThat(problemDetail.getStatus()).isEqualTo(400);
        assertThat(problemDetail.getDetail()).isEqualTo("productId size must be <= 64");
    }
}
