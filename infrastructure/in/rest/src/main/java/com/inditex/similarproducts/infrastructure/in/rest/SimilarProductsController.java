package com.inditex.similarproducts.infrastructure.in.rest;

import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.usecase.GetSimilarProductsUseCase;
import com.inditex.similarproducts.usecase.SimilarProductsResult;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@Validated
class SimilarProductsController {

    private final GetSimilarProductsUseCase useCase;

    SimilarProductsController(GetSimilarProductsUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/product/{productId}/similar")
    Mono<ResponseEntity<List<ProductDetail>>> getSimilar(
            @PathVariable @Size(max = 64) String productId) {
        return useCase.getSimilarProducts(productId)
                .map(SimilarProductsController::toResponse);
    }

    private static ResponseEntity<List<ProductDetail>> toResponse(SimilarProductsResult result) {
        HttpStatus status = result.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.products());
    }
}
