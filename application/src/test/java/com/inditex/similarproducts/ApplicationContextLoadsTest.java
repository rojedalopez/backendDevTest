package com.inditex.similarproducts;

import static org.assertj.core.api.Assertions.assertThat;

import com.inditex.similarproducts.usecase.GetSimilarProductsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "server.port=0")
class ApplicationContextLoadsTest {

    @Test
    void contextLoadsWithUseCaseBean(ApplicationContext context) {
        assertThat(context.getBean(GetSimilarProductsUseCase.class)).isNotNull();
    }
}
