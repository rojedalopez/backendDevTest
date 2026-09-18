package com.inditex.similarproducts.usecase;

import com.inditex.similarproducts.model.ProductDetail;
import java.util.List;

public record SimilarProductsResult(
        List<ProductDetail> products,
        boolean partial,
        int page,
        int size,
        int totalItems,
        int totalPages) {
}
