# Backend Pagination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add offset-based pagination (`page`/`size`) to `GET /product/{productId}/similar`, with the performance win coming from resolving only the requested page's similar-product details, not the full list.

**Architecture:** `domain/usecase`'s `GetSimilarProductsUseCase` slices the similar-ids list to the requested page before calling `ProductDetailPort`, so unrequested pages never trigger downstream detail lookups. `infrastructure/in/rest` adds `page`/`size` query params (validated via the existing `@Validated`/`ConstraintViolationException` mechanism) and a new response DTO carrying pagination metadata.

**Tech Stack:** Same stack as the rest of this service — WebFlux, Jakarta Bean Validation, Reactor (`Flux.flatMapSequential`), no new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-18-pagination-design.md`

## Global Constraints

- Pagination bounds (`page >= 0` via `@Min(0)`, `1 <= size <= 50` via `@Min(1)`/`@Max(50)`) are REST-boundary constraints only — `domain/usecase` receives already-valid ints and does no bounds-checking of its own.
- `SimilarProductsPage` (the new response DTO) is a REST-layer type (`infrastructure/in/rest`) — never reference it from `domain/usecase` or `domain/model`.
- Detail resolution (`ProductDetailPort.findProductDetail`) must only ever be called for the requested page's id slice — never resolve the full list and discard the rest. This is the entire performance point of this plan.
- `partial` keeps its existing meaning, now scoped to the requested page only: `true` if any id *within the page's slice* failed to resolve.
- `similarProducts.yaml` stays untouched (documents the originally agreed contract); the pagination deviation is documented in `IMPLEMENTATION.md` instead, same treatment as the existing 206 decision.
- Every module must maintain ≥95% line coverage and pass Checkstyle (zero error-severity violations) — unchanged project-wide gate, enforced by `mvn verify`.
- This environment's default `mvn`/`java` resolves to an incompatible JDK for JaCoCo; pin `JAVA_HOME=/Users/rojedalopez/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home` for every Maven command in this plan (matches `<java.version>21</java.version>` in the root `pom.xml`).

---

## File Structure

```
domain/usecase/src/main/java/com/inditex/similarproducts/usecase/SimilarProductsResult.java     [modify] add page/size/totalItems/totalPages
domain/usecase/src/main/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCase.java  [modify] page/size params + slicing
domain/usecase/src/test/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCaseTest.java [modify] updated + new pagination tests

infrastructure/in/rest/src/main/java/.../SimilarProductsPage.java         [create] new response DTO
infrastructure/in/rest/src/main/java/.../SimilarProductsController.java   [modify] page/size query params
infrastructure/in/rest/src/test/java/.../SimilarProductsControllerTest.java [modify] updated + new validation tests

application/src/test/java/com/inditex/similarproducts/SimilarProductsValidationTest.java [modify] add page/size full-stack test

IMPLEMENTATION.md [modify] document the pagination contract deviation
```

---

### Task 1: Domain Orchestration — Pagination

**Files:**
- Modify: `domain/usecase/src/main/java/com/inditex/similarproducts/usecase/SimilarProductsResult.java`
- Modify: `domain/usecase/src/main/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCase.java`
- Modify: `domain/usecase/src/test/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCaseTest.java`

**Interfaces:**
- Consumes: `SimilarProductIdsPort`, `ProductDetailPort`, `ProductDetail`, `ProductNotFoundException` — all unchanged from earlier work.
- Produces: `SimilarProductsResult(List<ProductDetail> products, boolean partial, int page, int size, int totalItems, int totalPages)` and `GetSimilarProductsUseCase#getSimilarProducts(String productId, int page, int size): Mono<SimilarProductsResult>` — this is a **signature change** (was single-arg `getSimilarProducts(String)`) that Task 2's controller depends on exactly as written here.

- [ ] **Step 1: Write the failing tests**

Replace the full content of `domain/usecase/src/test/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCaseTest.java`:

```java
package com.inditex.similarproducts.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.model.ProductNotFoundException;
import com.inditex.similarproducts.model.port.ProductDetailPort;
import com.inditex.similarproducts.model.port.SimilarProductIdsPort;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GetSimilarProductsUseCaseTest {

    private final SimilarProductIdsPort similarProductIdsPort = mock(SimilarProductIdsPort.class);
    private final ProductDetailPort productDetailPort = mock(ProductDetailPort.class);
    private final GetSimilarProductsUseCase useCase =
            new GetSimilarProductsUseCase(similarProductIdsPort, productDetailPort);

    @Test
    void returnsCompleteResultWhenAllDetailsSucceed() {
        when(similarProductIdsPort.findSimilarProductIds("1")).thenReturn(Mono.just(List.of("2", "3")));
        when(productDetailPort.findProductDetail("2"))
                .thenReturn(Mono.just(detail("2")).delayElement(Duration.ofMillis(50)));
        when(productDetailPort.findProductDetail("3")).thenReturn(Mono.just(detail("3")));

        StepVerifier.create(useCase.getSimilarProducts("1", 0, 10))
                .assertNext(result -> {
                    assertThat(result.partial()).isFalse();
                    assertThat(result.products()).containsExactly(detail("2"), detail("3"));
                    assertThat(result.page()).isEqualTo(0);
                    assertThat(result.size()).isEqualTo(10);
                    assertThat(result.totalItems()).isEqualTo(2);
                    assertThat(result.totalPages()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void returnsPartialResultWhenSomeDetailsFail() {
        when(similarProductIdsPort.findSimilarProductIds("1")).thenReturn(Mono.just(List.of("2", "3")));
        when(productDetailPort.findProductDetail("2")).thenReturn(Mono.just(detail("2")));
        when(productDetailPort.findProductDetail("3")).thenReturn(Mono.error(new RuntimeException("boom")));

        StepVerifier.create(useCase.getSimilarProducts("1", 0, 10))
                .assertNext(result -> {
                    assertThat(result.partial()).isTrue();
                    assertThat(result.products()).containsExactly(detail("2"));
                })
                .verifyComplete();
    }

    @Test
    void returnsEmptyPartialResultWhenAllDetailsFail() {
        when(similarProductIdsPort.findSimilarProductIds("1")).thenReturn(Mono.just(List.of("2", "3")));
        when(productDetailPort.findProductDetail("2")).thenReturn(Mono.error(new RuntimeException("boom")));
        when(productDetailPort.findProductDetail("3")).thenReturn(Mono.error(new RuntimeException("boom")));

        StepVerifier.create(useCase.getSimilarProducts("1", 0, 10))
                .assertNext(result -> {
                    assertThat(result.partial()).isTrue();
                    assertThat(result.products()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void propagatesNotFoundWhenBaseProductIdsLookupFails() {
        when(similarProductIdsPort.findSimilarProductIds("404"))
                .thenReturn(Mono.error(new ProductNotFoundException("404")));

        StepVerifier.create(useCase.getSimilarProducts("404", 0, 10))
                .expectError(ProductNotFoundException.class)
                .verify();
    }

    @Test
    void returnsCompleteEmptyResultWhenNoSimilarIds() {
        when(similarProductIdsPort.findSimilarProductIds("1")).thenReturn(Mono.just(List.of()));

        StepVerifier.create(useCase.getSimilarProducts("1", 0, 10))
                .assertNext(result -> {
                    assertThat(result.partial()).isFalse();
                    assertThat(result.products()).isEmpty();
                    assertThat(result.totalItems()).isEqualTo(0);
                    assertThat(result.totalPages()).isEqualTo(0);
                })
                .verifyComplete();
    }

    @Test
    void returnsFirstPageSliceWithoutResolvingLaterPages() {
        when(similarProductIdsPort.findSimilarProductIds("1"))
                .thenReturn(Mono.just(List.of("2", "3", "4", "5", "6")));
        when(productDetailPort.findProductDetail("2")).thenReturn(Mono.just(detail("2")));
        when(productDetailPort.findProductDetail("3")).thenReturn(Mono.just(detail("3")));

        StepVerifier.create(useCase.getSimilarProducts("1", 0, 2))
                .assertNext(result -> {
                    assertThat(result.products()).containsExactly(detail("2"), detail("3"));
                    assertThat(result.page()).isEqualTo(0);
                    assertThat(result.size()).isEqualTo(2);
                    assertThat(result.totalItems()).isEqualTo(5);
                    assertThat(result.totalPages()).isEqualTo(3);
                })
                .verifyComplete();
    }

    @Test
    void returnsSecondPageSlice() {
        when(similarProductIdsPort.findSimilarProductIds("1"))
                .thenReturn(Mono.just(List.of("2", "3", "4", "5", "6")));
        when(productDetailPort.findProductDetail("4")).thenReturn(Mono.just(detail("4")));
        when(productDetailPort.findProductDetail("5")).thenReturn(Mono.just(detail("5")));

        StepVerifier.create(useCase.getSimilarProducts("1", 1, 2))
                .assertNext(result -> {
                    assertThat(result.products()).containsExactly(detail("4"), detail("5"));
                    assertThat(result.page()).isEqualTo(1);
                    assertThat(result.totalPages()).isEqualTo(3);
                })
                .verifyComplete();
    }

    @Test
    void returnsEmptyResultForPageBeyondLastPage() {
        when(similarProductIdsPort.findSimilarProductIds("1")).thenReturn(Mono.just(List.of("2", "3")));

        StepVerifier.create(useCase.getSimilarProducts("1", 5, 2))
                .assertNext(result -> {
                    assertThat(result.products()).isEmpty();
                    assertThat(result.partial()).isFalse();
                    assertThat(result.totalItems()).isEqualTo(2);
                    assertThat(result.totalPages()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void scopesPartialFlagToRequestedPageOnly() {
        when(similarProductIdsPort.findSimilarProductIds("1"))
                .thenReturn(Mono.just(List.of("2", "3", "4")));
        when(productDetailPort.findProductDetail("4")).thenReturn(Mono.error(new RuntimeException("boom")));

        StepVerifier.create(useCase.getSimilarProducts("1", 1, 2))
                .assertNext(result -> {
                    assertThat(result.products()).isEmpty();
                    assertThat(result.partial()).isTrue();
                })
                .verifyComplete();
    }

    private static ProductDetail detail(String id) {
        return new ProductDetail(id, "name-" + id, BigDecimal.valueOf(9.99), true);
    }
}
```

Note on `returnsFirstPageSliceWithoutResolvingLaterPages` and `scopesPartialFlagToRequestedPageOnly`: neither test stubs `productDetailPort.findProductDetail` for ids outside the requested page (e.g. `"4"`, `"5"`, `"6"` in the first test). If the implementation resolves the full id list before slicing (the wrong, non-performant approach this plan explicitly forbids), Mockito returns `null` for those unstubbed calls, the reactive chain throws a `NullPointerException`, and the test fails with an error instead of completing. These tests are deliberately also regression guards for the "slice before resolving" requirement, not just behavioral checks.

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/Users/rojedalopez/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home mvn -pl domain/usecase -am test`
Expected: FAIL to compile — `getSimilarProducts(String, int, int)` doesn't exist yet, `SimilarProductsResult`'s 6-arg constructor doesn't exist yet.

- [ ] **Step 3: Update `SimilarProductsResult`**

Replace the full content of `domain/usecase/src/main/java/com/inditex/similarproducts/usecase/SimilarProductsResult.java`:

```java
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
```

- [ ] **Step 4: Update `GetSimilarProductsUseCase`**

Replace the full content of `domain/usecase/src/main/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCase.java`:

```java
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=/Users/rojedalopez/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home mvn -pl domain/usecase -am test`
Expected: `BUILD SUCCESS`, all 9 tests pass (5 existing behavior tests + 4 new pagination tests).

- [ ] **Step 6: Commit**

```bash
git add domain/usecase/src/main/java/com/inditex/similarproducts/usecase/SimilarProductsResult.java \
  domain/usecase/src/main/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCase.java \
  domain/usecase/src/test/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCaseTest.java
git commit -m "$(cat <<'EOF'
Slice similar-ids by page before resolving product details

GetSimilarProductsUseCase now takes page/size and only resolves
ProductDetailPort for the requested page's id slice, not the full
list — the actual performance win pagination is meant to provide.
SimilarProductsResult carries page/size/totalItems/totalPages.
partial now scopes to failures within the requested page only.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018R3CGLaKwwmZdCHknEGUjL
EOF
)"
```

---

### Task 2: REST Contract — Pagination

**Files:**
- Create: `infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsPage.java`
- Modify: `infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsController.java`
- Modify: `infrastructure/in/rest/src/test/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsControllerTest.java`
- Modify: `application/src/test/java/com/inditex/similarproducts/SimilarProductsValidationTest.java`
- Modify: `IMPLEMENTATION.md`

**Interfaces:**
- Consumes: `GetSimilarProductsUseCase#getSimilarProducts(String, int, int)` and `SimilarProductsResult` (both from Task 1, exact signature/fields as defined there).
- Produces: `SimilarProductsPage(List<ProductDetail> items, int page, int size, int totalItems, int totalPages)` — the new JSON response body shape. Nothing later in this plan consumes it, but it is the client-facing contract this task delivers.

- [ ] **Step 1: Write the failing tests**

Replace the full content of `infrastructure/in/rest/src/test/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsControllerTest.java`:

```java
package com.inditex.similarproducts.infrastructure.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.model.ProductNotFoundException;
import com.inditex.similarproducts.usecase.GetSimilarProductsUseCase;
import com.inditex.similarproducts.usecase.SimilarProductsResult;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class SimilarProductsControllerTest {

    private final GetSimilarProductsUseCase useCase = mock(GetSimilarProductsUseCase.class);
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new SimilarProductsController(useCase))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsOkWithCompleteResult() {
        ProductDetail detail = new ProductDetail("2", "Dress", BigDecimal.valueOf(19.99), true);
        when(useCase.getSimilarProducts("1", 0, 10))
                .thenReturn(Mono.just(new SimilarProductsResult(List.of(detail), false, 0, 10, 1, 1)));

        webTestClient.get().uri("/product/1/similar")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].id").isEqualTo("2")
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(10)
                .jsonPath("$.totalItems").isEqualTo(1)
                .jsonPath("$.totalPages").isEqualTo(1);
    }

    @Test
    void returnsPartialContentWhenResultIsPartial() {
        when(useCase.getSimilarProducts("4", 0, 10))
                .thenReturn(Mono.just(new SimilarProductsResult(List.of(), true, 0, 10, 2, 1)));

        webTestClient.get().uri("/product/4/similar")
                .exchange()
                .expectStatus().isEqualTo(206)
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.totalItems").isEqualTo(2);
    }

    @Test
    void returnsNotFoundWhenBaseProductMissing() {
        when(useCase.getSimilarProducts("404", 0, 10))
                .thenReturn(Mono.error(new ProductNotFoundException("404")));

        webTestClient.get().uri("/product/404/similar")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("Product not found: 404");
    }

    @Test
    void returnsServerErrorOnUnexpectedFailure() {
        when(useCase.getSimilarProducts("1", 0, 10)).thenReturn(Mono.error(new RuntimeException("boom")));

        webTestClient.get().uri("/product/1/similar")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(500)
                .jsonPath("$.detail").isEqualTo("An unexpected error occurred.");
    }

    @Test
    void logsUnexpectedExceptionsServerSide() {
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender.start();
        logger.addAppender(logAppender);

        when(useCase.getSimilarProducts("1", 0, 10)).thenReturn(Mono.error(new RuntimeException("boom")));

        webTestClient.get().uri("/product/1/similar")
                .exchange()
                .expectStatus().is5xxServerError();

        boolean errorLogged = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("Unhandled exception"));
        assertThat(errorLogged).isTrue();

        logger.detachAppender(logAppender);
    }

    @Test
    void mapsConstraintViolationToBadRequestProblemDetail() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Path.Node node = mock(Path.Node.class);
        when(node.getName()).thenReturn("productId");
        Path path = mock(Path.class);
        when(path.iterator()).thenReturn(List.of(node).iterator());
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("size must be between 0 and 64");
        ConstraintViolationException exception = new ConstraintViolationException(Set.of(violation));

        ProblemDetail problemDetail = handler.handleValidationFailure(exception);

        assertThat(problemDetail.getStatus()).isEqualTo(400);
        assertThat(problemDetail.getDetail()).isEqualTo("productId: size must be between 0 and 64");
    }

    @Test
    void returnsBadRequestWhenPageIsNegative() {
        webTestClient.get().uri("/product/1/similar?page=-1")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json");
    }

    @Test
    void returnsBadRequestWhenSizeIsZero() {
        webTestClient.get().uri("/product/1/similar?size=0")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json");
    }

    @Test
    void returnsBadRequestWhenSizeExceedsMaximum() {
        webTestClient.get().uri("/product/1/similar?size=51")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json");
    }
}
```

Add to `application/src/test/java/com/inditex/similarproducts/SimilarProductsValidationTest.java` (after the existing `rejectsOverlyLongProductId` test method, inside the class):

```java
    @Test
    void rejectsNegativePage() {
        webTestClient.get().uri("/product/1/similar?page=-1")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/Users/rojedalopez/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home mvn -pl infrastructure/in/rest -am test`
Expected: FAIL to compile — `SimilarProductsPage` doesn't exist yet, and `getSimilarProducts(String, int, int)` mocks won't match the controller's still-1-arg call.

- [ ] **Step 3: Create the response DTO and update the controller**

`infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsPage.java`:

```java
package com.inditex.similarproducts.infrastructure.in.rest;

import com.inditex.similarproducts.model.ProductDetail;
import java.util.List;

record SimilarProductsPage(List<ProductDetail> items, int page, int size, int totalItems, int totalPages) {
}
```

Replace the full content of `infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsController.java`:

```java
package com.inditex.similarproducts.infrastructure.in.rest;

import com.inditex.similarproducts.usecase.GetSimilarProductsUseCase;
import com.inditex.similarproducts.usecase.SimilarProductsResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return useCase.getSimilarProducts(productId, page, size)
                .map(SimilarProductsController::toResponse);
    }

    private static ResponseEntity<SimilarProductsPage> toResponse(SimilarProductsResult result) {
        HttpStatus status = result.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        SimilarProductsPage body = new SimilarProductsPage(
                result.products(), result.page(), result.size(), result.totalItems(), result.totalPages());
        return ResponseEntity.status(status).body(body);
    }
}
```

- [ ] **Step 4: Update `IMPLEMENTATION.md`**

In the "Operational endpoints and error contract" section of `IMPLEMENTATION.md`, add this bullet at the end of that section's list:

```markdown
- `GET /product/{productId}/similar` accepts `page` (default `0`) and `size`
  (default `10`, max `50`) query parameters and always returns a paginated
  envelope — `{items, page, size, totalItems, totalPages}` — rather than a
  bare array. This is a further deliberate deviation from
  `similarProducts.yaml`'s literal contract (same treatment as the 206
  decision); see
  `docs/superpowers/specs/2026-09-18-pagination-design.md` for the
  rationale, including why the performance benefit comes from slicing the
  similar-ids list *before* resolving product details, not after.
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=/Users/rojedalopez/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home mvn -pl infrastructure/in/rest -am test`
Expected: `BUILD SUCCESS`, all 9 tests pass (6 existing + 3 new validation tests).

Run: `JAVA_HOME=/Users/rojedalopez/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home mvn -pl domain/model,domain/usecase,infrastructure/in/rest,infrastructure/out/rest install -DskipTests` then `JAVA_HOME=/Users/rojedalopez/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home mvn -pl application test -Dtest=SimilarProductsValidationTest`
Expected: `BUILD SUCCESS`, 2 tests pass (`rejectsOverlyLongProductId` + the new `rejectsNegativePage`) — `-Dtest=SimilarProductsValidationTest` scopes execution to just this class, not the rest of the `application` module's suite. Do not combine `-Dtest=X` with `-am` — it applies the filter reactor-wide and fails on modules lacking that class; install upstream first, then run the target module alone.

- [ ] **Step 6: Commit**

```bash
git add infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsPage.java \
  infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsController.java \
  infrastructure/in/rest/src/test/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsControllerTest.java \
  application/src/test/java/com/inditex/similarproducts/SimilarProductsValidationTest.java \
  IMPLEMENTATION.md
git commit -m "$(cat <<'EOF'
Add page/size query params and paginated response envelope

GET /product/{productId}/similar now accepts page (default 0) and
size (default 10, max 50), validated via the existing @Validated/
ConstraintViolationException mechanism, and always returns
{items, page, size, totalItems, totalPages} instead of a bare array.
Documented as a deliberate similarProducts.yaml deviation in
IMPLEMENTATION.md, same treatment as the 206 decision.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018R3CGLaKwwmZdCHknEGUjL
EOF
)"
```

---

### Task 3: Full-Reactor Verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full build gate**

Run: `JAVA_HOME=/Users/rojedalopez/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home mvn -q -f pom.xml clean verify`
Expected: `BUILD SUCCESS` across all 6 modules (root + 5), all tests pass, JaCoCo ≥95% per module, 0 Checkstyle error-severity violations.

- [ ] **Step 2: Manually confirm pagination end-to-end**

Check `lsof -i :5000` first — if occupied by something you didn't start (an IDE-launched instance, AirPlay, etc.), use an alternate port (`--server.port=5050` or similar) and adjust the URLs below accordingly. Start the app with the mocks up (`docker-compose up -d simulado influxdb grafana` if not already running — check `docker ps` first, reuse an existing stack rather than starting a duplicate).

```bash
curl -s http://localhost:5000/product/1/similar | python3 -m json.tool
# expect: {"items": [...3 products...], "page": 0, "size": 10, "totalItems": 3, "totalPages": 1}

curl -s "http://localhost:5000/product/1/similar?size=1" | python3 -m json.tool
# expect: {"items": [...1 product...], "page": 0, "size": 1, "totalItems": 3, "totalPages": 3}

curl -i "http://localhost:5000/product/1/similar?page=-1"
# expect: 400, Content-Type: application/problem+json

curl -i "http://localhost:5000/product/1/similar?size=51"
# expect: 400, Content-Type: application/problem+json
```

Stop the app afterward (kill only the process you started).

- [ ] **Step 3: No commit for this task** — verification only. If Step 1 or Step 2 reveals a problem, fix it in the relevant task's own area with a new commit, not here.
