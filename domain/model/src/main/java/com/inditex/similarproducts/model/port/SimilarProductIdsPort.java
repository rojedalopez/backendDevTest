package com.inditex.similarproducts.model.port;

import java.util.List;
import reactor.core.publisher.Mono;

public interface SimilarProductIdsPort {

    Mono<List<String>> findSimilarProductIds(String productId);
}
