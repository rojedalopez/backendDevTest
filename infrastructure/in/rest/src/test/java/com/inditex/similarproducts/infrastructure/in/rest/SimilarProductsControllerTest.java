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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
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
        when(useCase.getSimilarProducts("1", 0, 10))
                .thenReturn(Mono.just(new SimilarProductsResult(List.of(detail), false, 0, 10, 1, 1)));

        webTestClient.get().uri("/product/1/similar")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].id").isEqualTo("2")
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(10)
                .jsonPath("$.totalItems").isEqualTo(1)
                .jsonPath("$.totalPages").isEqualTo(1);
    }

    @Test
    void returnsPartialContentWhenResultIsPartial() {
        when(useCase.getSimilarProducts("4", 0, 10))
                .thenReturn(Mono.just(new SimilarProductsResult(List.of(), true, 0, 10, 2, 1)));

        webTestClient.get().uri("/product/4/similar")
                .exchange()
                .expectStatus().isEqualTo(206)
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.totalItems").isEqualTo(2);
    }

    @Test
    void returnsNotFoundWhenBaseProductMissing() {
        when(useCase.getSimilarProducts("404", 0, 10))
                .thenReturn(Mono.error(new ProductNotFoundException("404")));

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
        when(useCase.getSimilarProducts("1", 0, 10)).thenReturn(Mono.error(new RuntimeException("boom")));

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

        when(useCase.getSimilarProducts("1", 0, 10)).thenReturn(Mono.error(new RuntimeException("boom")));

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
        Path.Node node = mock(Path.Node.class);
        when(node.getName()).thenReturn("productId");
        Path path = mock(Path.class);
        when(path.iterator()).thenReturn(List.of(node).iterator());
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("size must be between 0 and 64");
        ConstraintViolationException exception = new ConstraintViolationException(Set.of(violation));

        ProblemDetail problemDetail = handler.handleValidationFailure(exception);

        assertThat(problemDetail.getStatus()).isEqualTo(400);
        assertThat(problemDetail.getDetail()).isEqualTo("productId: size must be between 0 and 64");
    }

    @Test
    void returnsBadRequestWhenPageIsNegative() {
        webTestClient.get().uri("/product/1/similar?page=-1")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json");
    }

    @Test
    void returnsBadRequestWhenSizeIsZero() {
        webTestClient.get().uri("/product/1/similar?size=0")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json");
    }

    @Test
    void returnsBadRequestWhenSizeExceedsMaximum() {
        webTestClient.get().uri("/product/1/similar?size=51")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json");
    }

    @Test
    void returnsBadRequestWhenPageExceedsMaximum() {
        webTestClient.get().uri("/product/1/similar?page=100001")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json");
    }
}
