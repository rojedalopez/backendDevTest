package com.inditex.similarproducts.infrastructure.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsWebFilter;
import reactor.core.publisher.Mono;

class CorsConfigTest {

    private static final URI REQUEST_URI = URI.create("http://localhost:5000/product/1/similar");

    @Test
    void allowsConfiguredOriginForPreflightRequests() {
        CorsProperties properties = new CorsProperties(List.of("http://localhost:5173"));
        CorsWebFilter filter = new CorsConfig().corsWebFilter(properties);

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.OPTIONS, REQUEST_URI)
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("http://localhost:5173");
    }

    @Test
    void rejectsUnconfiguredOrigin() {
        CorsProperties properties = new CorsProperties(List.of("http://localhost:5173"));
        CorsWebFilter filter = new CorsConfig().corsWebFilter(properties);

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.OPTIONS, REQUEST_URI)
                .header("Origin", "http://evil.example.com")
                .header("Access-Control-Request-Method", "GET")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
    }
}
