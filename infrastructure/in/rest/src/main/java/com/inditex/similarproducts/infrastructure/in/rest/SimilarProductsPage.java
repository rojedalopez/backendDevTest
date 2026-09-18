package com.inditex.similarproducts.infrastructure.in.rest;

import com.inditex.similarproducts.model.ProductDetail;
import java.util.List;

record SimilarProductsPage(List<ProductDetail> items, int page, int size, int totalItems, int totalPages) {
}
