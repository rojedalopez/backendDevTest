package com.inditex.similarproducts.infrastructure.out.rest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.model.port.ProductDetailPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
class ProductDetailAdapter implements ProductDetailPort {

    private static final String INSTANCE_NAME = "productDetailService";

    private final WebClient webClient;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    private final Cache<String, ProductDetail> cache;

    ProductDetailAdapter(WebClient mocksWebClient, TimeLimiterRegistry timeLimiterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry, MocksProperties properties) {
        this.webClient = mocksWebClient;
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.productDetailCache().ttl())
                .maximumSize(properties.productDetailCache().maxSize())
                .build();
    }

    @Override
    public Mono<ProductDetail> findProductDetail(String productId) {
        ProductDetail cached = cache.getIfPresent(productId);
        if (cached != null) {
            return Mono.just(cached);
        }
        return fetch(productId).doOnNext(detail -> cache.put(productId, detail));
    }

    private Mono<ProductDetail> fetch(String productId) {
        return webClient.get()
                .uri("/product/{productId}", productId)
                .retrieve()
                .bodyToMono(ProductDetail.class)
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
