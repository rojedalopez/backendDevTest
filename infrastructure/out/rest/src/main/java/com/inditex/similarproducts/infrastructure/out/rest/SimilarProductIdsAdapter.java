package com.inditex.similarproducts.infrastructure.out.rest;

import com.inditex.similarproducts.model.ProductNotFoundException;
import com.inditex.similarproducts.model.port.SimilarProductIdsPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
class SimilarProductIdsAdapter implements SimilarProductIdsPort {

    private static final String INSTANCE_NAME = "similarIdsService";
    private static final ParameterizedTypeReference<List<String>> ID_LIST_TYPE =
            new ParameterizedTypeReference<>() { };

    private final WebClient webClient;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;

    SimilarProductIdsAdapter(WebClient mocksWebClient, TimeLimiterRegistry timeLimiterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.webClient = mocksWebClient;
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
    }

    @Override
    public Mono<List<String>> findSimilarProductIds(String productId) {
        return webClient.get()
                .uri("/product/{productId}/similarids", productId)
                .retrieve()
                .bodyToMono(ID_LIST_TYPE)
                .onErrorMap(WebClientResponseException.NotFound.class,
                        notFound -> new ProductNotFoundException(productId))
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
