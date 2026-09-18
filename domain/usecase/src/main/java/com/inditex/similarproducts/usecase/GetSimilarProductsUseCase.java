package com.inditex.similarproducts.usecase;

import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.model.port.ProductDetailPort;
import com.inditex.similarproducts.model.port.SimilarProductIdsPort;
import java.util.List;
import java.util.Optional;
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

    public Mono<SimilarProductsResult> getSimilarProducts(String productId, int page, int size) {
        return similarProductIdsPort.findSimilarProductIds(productId)
                .flatMap(ids -> {
                    int totalItems = ids.size();
                    int totalPages = totalItems == 0 ? 0 : (totalItems + size - 1) / size;
                    int fromIndex = Math.min(page * size, totalItems);
                    int toIndex = Math.min(fromIndex + size, totalItems);
                    List<String> pageIds = ids.subList(fromIndex, toIndex);

                    return Flux.fromIterable(pageIds)
                            .flatMapSequential(this::fetchDetailOrEmpty, DETAIL_FETCH_CONCURRENCY)
                            .collectList()
                            .map(results -> toResult(
                                    pageIds.size(), results, page, size, totalItems, totalPages));
                });
    }

    private Mono<Optional<ProductDetail>> fetchDetailOrEmpty(String id) {
        return productDetailPort.findProductDetail(id)
                .map(Optional::of)
                .onErrorResume(error -> Mono.just(Optional.empty()));
    }

    private static SimilarProductsResult toResult(int requestedCount, List<Optional<ProductDetail>> results,
            int page, int size, int totalItems, int totalPages) {
        List<ProductDetail> products = results.stream()
                .flatMap(Optional::stream)
                .toList();
        boolean partial = products.size() < requestedCount;
        return new SimilarProductsResult(products, partial, page, size, totalItems, totalPages);
    }
}
