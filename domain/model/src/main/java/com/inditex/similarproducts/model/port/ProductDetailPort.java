package com.inditex.similarproducts.model.port;

import com.inditex.similarproducts.model.ProductDetail;
import reactor.core.publisher.Mono;

public interface ProductDetailPort {

    Mono<ProductDetail> findProductDetail(String productId);
}
