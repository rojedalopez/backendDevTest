package com.inditex.similarproducts.infrastructure.out.rest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "product-catalog")
public record ProductCatalogProperties(String baseUrl, int maxConnections, ProductDetailCache productDetailCache) {

    public record ProductDetailCache(Duration ttl, long maxSize) {
    }
}
