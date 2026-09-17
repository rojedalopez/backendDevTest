package com.inditex.similarproducts.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.model.ProductNotFoundException;
import com.inditex.similarproducts.model.port.ProductDetailPort;
import com.inditex.similarproducts.model.port.SimilarProductIdsPort;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GetSimilarProductsUseCaseTest {

    private final SimilarProductIdsPort similarProductIdsPort = mock(SimilarProductIdsPort.class);
    private final ProductDetailPort productDetailPort = mock(ProductDetailPort.class);
    private final GetSimilarProductsUseCase useCase =
            new GetSimilarProductsUseCase(similarProductIdsPort, productDetailPort);

    @Test
    void returnsCompleteResultWhenAllDetailsSucceed() {
        when(similarProductIdsPort.findSimilarProductIds("1")).thenReturn(Mono.just(List.of("2", "3")));
        when(productDetailPort.findProductDetail("2")).thenReturn(Mono.just(detail("2")));
        when(productDetailPort.findProductDetail("3")).thenReturn(Mono.just(detail("3")));

        StepVerifier.create(useCase.getSimilarProducts("1"))
                .assertNext(result -> {
                    assertThat(result.partial()).isFalse();
                    assertThat(result.products()).containsExactlyInAnyOrder(detail("2"), detail("3"));
                })
                .verifyComplete();
    }

    @Test
    void returnsPartialResultWhenSomeDetailsFail() {
        when(similarProductIdsPort.findSimilarProductIds("1")).thenReturn(Mono.just(List.of("2", "3")));
        when(productDetailPort.findProductDetail("2")).thenReturn(Mono.just(detail("2")));
        when(productDetailPort.findProductDetail("3")).thenReturn(Mono.error(new RuntimeException("boom")));

        StepVerifier.create(useCase.getSimilarProducts("1"))
                .assertNext(result -> {
                    assertThat(result.partial()).isTrue();
                    assertThat(result.products()).containsExactly(detail("2"));
                })
                .verifyComplete();
    }

    @Test
    void returnsEmptyPartialResultWhenAllDetailsFail() {
        when(similarProductIdsPort.findSimilarProductIds("1")).thenReturn(Mono.just(List.of("2", "3")));
        when(productDetailPort.findProductDetail("2")).thenReturn(Mono.error(new RuntimeException("boom")));
        when(productDetailPort.findProductDetail("3")).thenReturn(Mono.error(new RuntimeException("boom")));

        StepVerifier.create(useCase.getSimilarProducts("1"))
                .assertNext(result -> {
                    assertThat(result.partial()).isTrue();
                    assertThat(result.products()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void propagatesNotFoundWhenBaseProductIdsLookupFails() {
        when(similarProductIdsPort.findSimilarProductIds("404"))
                .thenReturn(Mono.error(new ProductNotFoundException("404")));

        StepVerifier.create(useCase.getSimilarProducts("404"))
                .expectError(ProductNotFoundException.class)
                .verify();
    }

    @Test
    void returnsCompleteEmptyResultWhenNoSimilarIds() {
        when(similarProductIdsPort.findSimilarProductIds("1")).thenReturn(Mono.just(List.of()));

        StepVerifier.create(useCase.getSimilarProducts("1"))
                .assertNext(result -> {
                    assertThat(result.partial()).isFalse();
                    assertThat(result.products()).isEmpty();
                })
                .verifyComplete();
    }

    private static ProductDetail detail(String id) {
        return new ProductDetail(id, "name-" + id, BigDecimal.valueOf(9.99), true);
    }
}
