# Backend Pagination — Design

**Status:** Approved for planning
**Date:** 2026-09-18
**Author:** Roberto Ojeda Lopez (with Claude Code)

## 1. Problem

`GET /product/{productId}/similar` currently resolves and returns the full similar-products list in one response. This spec adds pagination to that endpoint, with the goal of genuinely reducing downstream work per request, not just truncating an already-fully-computed response.

This is one of two independent sub-projects under the umbrella request "implement pagination in both layers, frontend and backend." The frontend half (`~/globant/webapp`, a separate React SPA calling a completely different, pre-existing external API at `https://itx-frontend-test.onrender.com`) is out of scope for this spec — it is unrelated to this backend and gets its own spec/plan cycle, in its own project.

## 2. Where the Performance Win Actually Comes From

The naive approach — resolve every similar product's detail as today, then slice the assembled list before returning it — saves nothing: every downstream call already happened by the time the slice is taken. The real win is slicing the **id list** before resolving details, so a request for page 0 of a 50-item similar-ids list only triggers detail lookups (and their associated Resilience4j timeout/circuit-breaker/cache machinery) for the 10 ids on that page, not all 50.

Ports and adapters are unaffected: `SimilarProductIdsPort.findSimilarProductIds` still returns the full id list in one cheap call (a list of strings, no per-item downstream cost), and neither `ProductDetailPort` nor either adapter needs to know pagination exists. This is purely an orchestration-level (`domain/usecase`) concern.

**Honest caveat:** `shared/simulado/mocks.json`'s similar-ids lists only have 3 items each. Any page size ≥ 3 resolves every item regardless of pagination, so the existing k6 fixtures cannot demonstrate a measurable before/after improvement. The mechanism is correct and pays off with real/larger datasets; this spec does not claim a benchmarked win against this project's own test data.

## 3. Style: Offset-Based (page/size)

Chosen over cursor-based pagination. The dataset per request is small and bounded (one product's similar-ids list), and the total count is cheap to know upfront (just the id list's length — no detail resolution needed to compute it). Cursor-based pagination earns its complexity (opaque cursor encoding, no "jump to page N") on large or frequently-mutating datasets; neither applies here.

- `page`: 0-indexed, default `0`, minimum `0`.
- `size`: default `10`, minimum `1`, maximum `50`.

Both bound via `@Min`/`@Max` on `@RequestParam`, reusing the exact `@Validated` + `ConstraintViolationException` → 400 `ProblemDetail` mechanism already built for `productId`'s `@Size(max = 64)` constraint (see `infrastructure/in/rest/.../GlobalExceptionHandler.java`'s `handleValidationFailure`).

## 4. Orchestration (`domain/usecase`)

`GetSimilarProductsUseCase.getSimilarProducts` gains two parameters:

```java
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
                        .map(results -> toResult(pageIds.size(), results, page, size, totalItems, totalPages));
            });
}
```

`fetchDetailOrEmpty` and `DETAIL_FETCH_CONCURRENCY` (16) are unchanged — the existing per-item failure isolation and bounded-concurrency, order-preserving resolution (`flatMapSequential`, per the ordering-by-similarity requirement) apply identically to whatever slice is requested.

`SimilarProductsResult` gains the pagination fields:

```java
public record SimilarProductsResult(
        List<ProductDetail> products,
        boolean partial,
        int page,
        int size,
        int totalItems,
        int totalPages) {
}
```

`partial` keeps its existing meaning, now scoped to the requested page: `true` if any id *within this page's slice* failed to resolve. A page beyond the last page (`fromIndex >= totalItems`) yields an empty `products` list with `partial = false` (nothing was requested in that slice, so nothing "partially" failed — same reasoning as today's empty-similar-ids-list case).

**404 and base-product-not-found behavior is unchanged**: if `findSimilarProductIds` fails with `ProductNotFoundException`, it propagates untouched before pagination logic ever runs, exactly as today.

## 5. REST Contract (`infrastructure/in/rest`)

```java
@GetMapping("/product/{productId}/similar")
Mono<ResponseEntity<SimilarProductsPage>> getSimilar(
        @PathVariable @Size(max = 64) String productId,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
    return useCase.getSimilarProducts(productId, page, size)
            .map(SimilarProductsController::toResponse);
}
```

New response DTO, replacing the current bare `List<ProductDetail>` body:

```java
public record SimilarProductsPage(
        List<ProductDetail> items,
        int page,
        int size,
        int totalItems,
        int totalPages) {
}
```

`partial` is deliberately **not** serialized into the body — it already drives the 200/206 status code, and duplicating it as a body field would be redundant. This is why the response uses a dedicated DTO rather than serializing `SimilarProductsResult` directly (rule 6's "reuse the domain record unless it diverges from the contract" — here it diverges, so it gets its own DTO, same reasoning that already applies elsewhere in this codebase).

`toResponse` maps `SimilarProductsResult` → `SimilarProductsPage` + status exactly as today's 200/206 logic, just carrying the extra fields through.

**Every call is paginated**, with no way to opt out — `page=0&size=10` are the defaults when unspecified, not a special "give me everything" mode. One consistent response shape regardless of whether query params are present.

## 6. Contract Documentation

`similarProducts.yaml` is left untouched — it represents the originally agreed, fixed contract from the take-home exercise, and this project's established convention (per the 206 decision) is to document deviations rather than rewrite that file. This pagination change is documented in `IMPLEMENTATION.md`'s existing "Operational endpoints and error contract" section, extended with a new bullet describing the paginated response shape and the query params.

## 7. Testing

- **`domain/usecase`** (`GetSimilarProductsUseCaseTest`): new cases for page-0 slicing with a list larger than `size`, a page beyond the last page (empty `products`, correct `totalPages`, `partial = false`), `totalPages` boundary arithmetic (exact multiple of `size` vs. remainder), and confirming `partial` is scoped to failures within the requested slice only (an id outside the page that would have failed must not affect `partial`).
- **`infrastructure/out/rest`**: no changes — ports/adapters are untouched by this spec.
- **`infrastructure/in/rest`** (`SimilarProductsControllerTest`): update existing 200/206 tests for the new `SimilarProductsPage` body shape; add validation tests for `page < 0` and `size` outside `[1, 50]` → 400 `ProblemDetail`, reusing the existing `ConstraintViolationException`-based direct handler test pattern plus real query-param requests through `WebTestClient`.
- **`application`** (`SimilarProductsValidationTest`): optionally extend with a full-stack `page`/`size` out-of-range case, mirroring the existing `productId` full-stack validation test — confirms the AOP validation trigger actually fires for `@RequestParam` constraints the same way it does for `@PathVariable` ones (not something the lighter `infrastructure/in/rest` test harness can prove, same reasoning as the earlier `productId` validation work).

## 8. Coding Rules (this spec's additions)

These extend, not replace, the original service spec's §8 rules:

1. Pagination bounds (`page >= 0`, `1 <= size <= 50`) are method-contract constraints on the REST boundary (`@Min`/`@Max`), not domain logic — `domain/usecase` receives already-valid `page`/`size` ints and does no bounds-checking of its own, consistent with rule 1 (domain stays framework-agnostic) and how `productId`'s `@Size` validation already works.
2. `SimilarProductsPage` is a REST-layer DTO (`infrastructure/in/rest`), not a domain type — it must never be referenced from `domain/usecase` or `domain/model`.
3. Detail resolution must only ever be attempted for the requested page's id slice — never resolve, then discard, ids outside the page (this is the entire point of the spec; a future change that reintroduces "resolve everything, slice after" defeats it silently unless caught in review).

## 9. Open Items

- Exact default/max (`10`/`50`) are starting values agreed in chat, not benchmarked against real traffic — tunable later without a contract change (they're implementation constants/validation bounds, not part of the response shape).
- The frontend half of the original request (`webapp`) is explicitly out of scope here; see §1.
