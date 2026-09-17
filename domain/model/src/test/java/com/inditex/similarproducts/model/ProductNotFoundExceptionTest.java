package com.inditex.similarproducts.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductNotFoundExceptionTest {

    @Test
    void messageContainsProductId() {
        ProductNotFoundException exception = new ProductNotFoundException("42");

        assertThat(exception.getMessage()).contains("42");
    }
}
