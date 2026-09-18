package com.inditex.similarproducts.infrastructure.out.rest;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.model.port.ProductDetailPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
class ProductDetailAdapter implements ProductDetailPort {

    private static final String INSTANCE_NAME = "productDetailService";
    private static final Logger log = LoggerFactory.getLogger(ProductDetailAdapter.class);

    private final WebClient webClient;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    private final AsyncCache<String, ProductDetail> cache;

    ProductDetailAdapter(WebClient productCatalogWebClient, TimeLimiterRegistry timeLimiterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry, ProductCatalogProperties properties) {
        this.webClient = productCatalogWebClient;
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.productDetailCache().ttl())
                .maximumSize(properties.productDetailCache().maxSize())
                .buildAsync();

        this.circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.info("Circuit breaker '{}' transitioned from {} to {}",
                        INSTANCE_NAME, event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
        this.timeLimiter.getEventPublisher()
                .onTimeout(event -> log.warn("TimeLimiter '{}' timed out", INSTANCE_NAME));
    }

    @Override
    public Mono<ProductDetail> findProductDetail(String productId) {
        return Mono.fromFuture(() -> cache.get(productId, (id, executor) -> fetch(id).toFuture()), true)
                .doOnError(error -> cache.synchronous().invalidate(productId));
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
