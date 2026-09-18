package com.inditex.similarproducts.infrastructure.in.rest;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cors")
public record CorsProperties(List<String> allowedOrigins) {
}
