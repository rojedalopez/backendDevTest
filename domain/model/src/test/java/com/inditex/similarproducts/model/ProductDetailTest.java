package com.inditex.similarproducts.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductDetailTest {

    @Test
    void exposesAllFields() {
        ProductDetail detail = new ProductDetail("1", "Shirt", BigDecimal.valueOf(9.99), true);

        assertThat(detail.id()).isEqualTo("1");
        assertThat(detail.name()).isEqualTo("Shirt");
        assertThat(detail.price()).isEqualByComparingTo("9.99");
        assertThat(detail.availability()).isTrue();
    }

    @Test
    void equalRecordsAreEqual() {
        ProductDetail first = new ProductDetail("1", "Shirt", BigDecimal.valueOf(9.99), true);
        ProductDetail second = new ProductDetail("1", "Shirt", BigDecimal.valueOf(9.99), true);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first.toString()).contains("Shirt");
    }
}
