package com.inditex.similarproducts.model;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String productId) {
        super("Product not found: " + productId);
    }
}
