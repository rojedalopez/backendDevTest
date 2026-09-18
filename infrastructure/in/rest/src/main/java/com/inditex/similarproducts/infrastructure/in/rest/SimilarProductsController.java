package com.inditex.similarproducts.infrastructure.in.rest;

import com.inditex.similarproducts.usecase.GetSimilarProductsUseCase;
import com.inditex.similarproducts.usecase.SimilarProductsResult;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
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
    Mono<ResponseEntity<SimilarProductsPage>> getSimilar(
            @PathVariable @Size(max = 64) String productId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        // Manually validate parameters for WebFlux compatibility
        doValidateParameters(productId, page, size);

        return useCase.getSimilarProducts(productId, page, size)
                .map(SimilarProductsController::toResponse);
    }

    private static void doValidateParameters(String productId, int page, int size) {
        Set<ConstraintViolation<?>> empty = Collections.emptySet();
        if (productId.length() > 64) {
            throw new ConstraintViolationException(
                    "productId: size must be between 0 and 64", empty);
        }
        if (page < 0) {
            throw new ConstraintViolationException(
                    "page: must be greater than or equal to 0", empty);
        }
        if (page > 100000) {
            throw new ConstraintViolationException(
                    "page: must be less than or equal to 100000", empty);
        }
        if (size < 1) {
            throw new ConstraintViolationException(
                    "size: must be greater than or equal to 1", empty);
        }
        if (size > 50) {
            throw new ConstraintViolationException(
                    "size: must be less than or equal to 50", empty);
        }
    }

    private static ResponseEntity<SimilarProductsPage> toResponse(SimilarProductsResult result) {
        HttpStatus status = result.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        SimilarProductsPage body = new SimilarProductsPage(
                result.products(), result.page(), result.size(), result.totalItems(), result.totalPages());
        return ResponseEntity.status(status).body(body);
    }
}
