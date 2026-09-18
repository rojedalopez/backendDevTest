package com.inditex.similarproducts;

import com.inditex.similarproducts.model.port.ProductDetailPort;
import com.inditex.similarproducts.model.port.SimilarProductIdsPort;
import com.inditex.similarproducts.usecase.GetSimilarProductsUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class UseCaseConfig {

    @Bean
    GetSimilarProductsUseCase getSimilarProductsUseCase(SimilarProductIdsPort similarProductIdsPort,
            ProductDetailPort productDetailPort) {
        return new GetSimilarProductsUseCase(similarProductIdsPort, productDetailPort);
    }
}
