package com.inditex.similarproducts.usecase;

import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.model.port.ProductDetailPort;
import com.inditex.similarproducts.model.port.SimilarProductIdsPort;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class GetSimilarProductsUseCase {

    private static final int DETAIL_FETCH_CONCURRENCY = 16;

    private final SimilarProductIdsPort similarProductIdsPort;
    private final ProductDetailPort productDetailPort;

    public GetSimilarProductsUseCase(SimilarProductIdsPort similarProductIdsPort,
            ProductDetailPort productDetailPort) {
        this.similarProductIdsPort = similarProductIdsPort;
        this.productDetailPort = productDetailPort;
    }

    public Mono<SimilarProductsResult> getSimilarProducts(String productId) {
        return similarProductIdsPort.findSimilarProductIds(productId)
                .flatMap(ids -> Flux.fromIterable(ids)
                        .flatMap(id -> fetchDetailOrEmpty(id), DETAIL_FETCH_CONCURRENCY)
                        .collectList()
                        .map(results -> toResult(ids.size(), results)));
    }

    private Mono<Optional<ProductDetail>> fetchDetailOrEmpty(String id) {
        return productDetailPort.findProductDetail(id)
                .map(Optional::of)
                .onErrorResume(error -> Mono.just(Optional.empty()));
    }

    private static SimilarProductsResult toResult(int requestedCount, List<Optional<ProductDetail>> results) {
        List<ProductDetail> products = results.stream()
                .flatMap(Optional::stream)
                .collect(Collectors.toList());
        boolean partial = products.size() < requestedCount;
        return new SimilarProductsResult(products, partial);
    }
}
