# Similar Products Service — Design

**Status:** Approved for planning
**Date:** 2026-09-17
**Author:** Roberto Ojeda Lopez (with Claude Code)

## 1. Problem

Implement `yourApp`: a Spring Boot service listening on port 5000 that exposes
`GET /product/{productId}/similar` per [`similarProducts.yaml`](../../../similarProducts.yaml).
For a given product, it must return the product detail of each similar product,
ordered by similarity. It gets there by composing two existing mock APIs on
port 3001 ([`existingApis.yaml`](../../../existingApis.yaml)):

- `GET /product/{productId}/similarids` — list of similar product ids.
- `GET /product/{productId}` — product detail for a single id.

`shared/simulado/mocks.json` seeds the mocks with deliberately awkward data:
some product-detail lookups are slow (1s / 5s / 50s delays), some 404, one
500s. The load test (`shared/k6/test.js`) drives 200 concurrent VUs per
scenario at these cases, so the design's core problem is **fanning out N
downstream detail calls per request while staying fast and resilient when
some of those calls are slow or failing** — this is what the evaluation
criteria "Performance" and "Resilience" are testing.

## 2. Architecture & Modules

Hexagonal / ports-and-adapters, multi-module Maven, built on the existing
scaffold with one rename (`infrastructure/out/grafana` → `infrastructure/out/rest`,
since that module is the outbound client to the mocks, not a metrics
component):

```
application/              Spring Boot bootstrap (main class, application.yml), runs on :5000
domain/model/              pure Java: ProductDetail record, out-ports, domain exceptions
domain/usecase/             pure Java: orchestration logic (depends only on domain/model)
infrastructure/in/rest/     inbound adapter: REST controller (depends on domain/usecase)
infrastructure/out/rest/    outbound adapter: WebClient calls to the mocks (depends on domain/model)
```

Dependency direction is inward only: `infrastructure/*` depends on
`domain/*`; `domain/usecase` depends on `domain/model`; `domain/model` and
`domain/usecase` depend on nothing framework-specific.

There is currently no root aggregator `pom.xml`. One will be added:
`<packaging>pom</packaging>`, `<modules>` listing all five modules, and
`<dependencyManagement>` importing the Spring Boot BOM and the Resilience4j
BOM, centralizing the Java 24 compiler properties currently duplicated in
every child pom.

**Stack decisions:**
- **Reactive** (Spring WebFlux + `WebClient`), chosen over virtual
  threads/blocking or a manual thread-pool + `CompletableFuture` approach,
  for mature non-blocking composition and timeout/backpressure operators
  under the 200-VU concurrent load.
- **Resilience4j** for per-call timeouts and circuit breaking.
- **No caching.** Rejected deliberately — the point of this exercise is to
  demonstrate the resilience/concurrency story under real load, not to mask
  slow/failing downstream calls behind a cache.

## 3. Domain Model & Ports (`domain/model`)

```java
// domain/model
public record ProductDetail(String id, String name, BigDecimal price, boolean availability) {}

public interface SimilarProductIdsPort {
    Mono<List<String>> findSimilarProductIds(String productId);
}

public interface ProductDetailPort {
    Mono<ProductDetail> findProductDetail(String productId);
}

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String productId) { super(productId); }
}
```

`price` is `BigDecimal`, not `double` — avoids floating-point representation
error for currency values.

The original empty `ports.out.SimilarProductsService` stub is replaced by
these two single-purpose ports: it mapped to two distinct external calls
with distinct failure/resilience characteristics, so splitting keeps each
port's contract and each adapter's resilience config independent.

## 4. Orchestration (`domain/usecase`)

```java
public record SimilarProductsResult(List<ProductDetail> products, boolean partial) {}

public class GetSimilarProductsUseCase {
    Mono<SimilarProductsResult> getSimilarProducts(String productId) {
        return similarProductIdsPort.findSimilarProductIds(productId)
            .flatMap(ids -> Flux.fromIterable(ids)
                .flatMap(id -> productDetailPort.findProductDetail(id)
                        .map(Optional::of)
                        .onErrorResume(e -> Mono.just(Optional.<ProductDetail>empty())),
                    DETAIL_FETCH_CONCURRENCY)
                .collectList()
                .map(results -> toResult(ids.size(), results)));
    }
}
```

Rules encoded here:

- **404 on the base product**: if `findSimilarProductIds` fails with
  `ProductNotFoundException` (mapped from the mock's 404 on
  `/product/{id}/similarids`), it propagates untouched — no detail calls are
  attempted.
- **Any other failure on the ids call** (5xx, timeout) also propagates, and
  is mapped to a 500 at the REST edge — this isn't exercised by the given
  k6 scenarios but is a defensive default, not a heavily engineered path.
- **Per-id detail failures are swallowed**, not surfaced: a 404, 500, or
  timeout on one similar product's detail call just drops that product from
  the result. The batch never fails because one item failed.
- **`partial` flag**: `false` when every requested id resolved
  successfully (including the case of zero requested ids — nothing failed,
  so it's not "partial"); `true` when one or more ids failed to resolve,
  which may leave the array empty (allowed by the contract's `minItems: 0`).
- `DETAIL_FETCH_CONCURRENCY` bounds how many detail calls are in flight at
  once for a single request (proposed default: 16) — protects against
  unbounded fan-out if a product has an unusually long similar-ids list.

## 5. Outbound Adapter (`infrastructure/out/rest`)

- `WebClient` targeting a configurable `mocks.base-url`
  (default `http://localhost:3001`), backed by a Reactor Netty connection
  pool sized for the 200-VU load (proposed default `maxConnections=500`).
- `SimilarProductIdsAdapter implements SimilarProductIdsPort` — GET
  `/product/{id}/similarids`; maps a 404 response to `ProductNotFoundException`.
- `ProductDetailAdapter implements ProductDetailPort` — GET `/product/{id}`.
- Both adapter methods are wrapped with **Resilience4j** `TimeLimiter` +
  `CircuitBreaker`, as two distinct named instances (`similarIdsService`,
  `productDetailService`) so their thresholds can be tuned independently:
  - Proposed timeout: ~2s per call (bounds worst-case per-item latency well
    under the 5s/50s delay cases, without cutting off the 1s case).
  - Proposed circuit breaker: sliding window ~20 calls, 50% failure-rate
    threshold, short wait-duration-in-open-state. Once the always-50s-delay
    product or the always-500 product start failing repeatedly, the breaker
    opens and later calls fail fast instead of paying the full timeout —
    this is what keeps the "verySlow" and "error" scenarios performant
    under sustained load.
- All of the above thresholds are externalized to `application.yml`, never
  hardcoded in adapter classes.

## 6. Inbound Adapter (`infrastructure/in/rest`)

`SimilarProductsController`:

| Use case outcome | HTTP status |
|---|---|
| `SimilarProductsResult(products, partial=false)` | 200, body = `products` |
| `SimilarProductsResult(products, partial=true)` | 206, body = `products` (possibly empty) |
| `ProductNotFoundException` | 404 |
| any other error | 500 |

**Deliberate contract deviation:** `similarProducts.yaml` only documents 200
and 404 responses. Returning 206 for partial results is an explicit,
agreed extension beyond the given contract, not an oversight.

The domain `ProductDetail` record is serialized directly as the response
body (its fields match the contract's `ProductDetail` schema exactly) —
no separate response DTO/mapping layer, since one would add nothing here.

## 7. Testing Strategy

- TDD throughout implementation (`superpowers:test-driven-development`).
- **`domain/usecase`**: unit tests with mocked ports and `StepVerifier`,
  covering: all succeed (200/complete), some fail (206/partial), all fail
  (206/empty), ids-call 404 (`ProductNotFoundException` propagates), empty
  similar-ids list (200/complete/empty).
- **`infrastructure/out/rest`**: adapter tests (WireMock or
  `MockWebServer`) proving the timeout and circuit breaker actually trip
  against slow/erroring responses, and that a 404 maps to
  `ProductNotFoundException`.
- **`infrastructure/in/rest`**: `WebTestClient` slice tests for the
  status-code mapping table in §6.
- **Acceptance**: the provided `docker-compose` + k6 flow is the real
  acceptance test. Run it for real
  (`docker-compose up -d simulado influxdb grafana`, start the app on
  :5000, `docker-compose run --rm k6 run scripts/test.js`) and check the
  Grafana dashboard before calling the work done.

## 8. Coding Rules

These apply for the lifetime of this feature and should guide review of any
change touching it:

1. `domain/model` and `domain/usecase` must have **zero** dependencies on
   Spring, WebClient, or Resilience4j types — pure Java, framework-agnostic,
   unit-testable without a Spring context.
2. No raw `WebClient` calls outside the two adapter classes in
   `infrastructure/out/rest` — every downstream call goes through a
   resilience-wrapped port implementation.
3. No `.block()` calls outside test code — the request path stays
   non-blocking end to end.
4. Config values (base URL, timeouts, circuit-breaker thresholds,
   connection-pool size) live in `application.yml`, never hardcoded in
   adapter or use-case code.
5. Prefer Java records for immutable data; use `BigDecimal` for money.
6. Reuse a domain record as a REST response body when it matches the
   contract 1:1 (as `ProductDetail` does here); only introduce a separate
   DTO if/when they diverge.
7. A single failed similar-product detail lookup must never fail the whole
   `/similar` request — only a failure on the base product's ids lookup
   (404) or an unexpected error there does.

## 9. Implementation Workflow

"Agents" here refers to Claude Code subagents used to execute the
resulting implementation plan, not a software component. Next steps:

1. Invoke `writing-plans` to turn this spec into a concrete task list.
2. Drive implementation via `subagent-driven-development`. Module/build
   scaffolding (root pom, per-module poms, Spring Boot bootstrap) can run
   in parallel with authoring the domain model. From there, `usecase` →
   adapters → controller have real sequential dependencies on each other,
   so most of the work runs as a single ordered plan rather than fully
   parallel agents.
3. Each step follows TDD (`superpowers:test-driven-development`) and ends
   with `superpowers:verification-before-completion` before being marked
   done.

## 10. Open Items / Explicitly Deferred

- Exact timeout (proposed 2s) and circuit-breaker thresholds (proposed
  20-call window / 50% failure rate) are defaults to validate against the
  actual k6 run once implemented, not final numbers.
- `DETAIL_FETCH_CONCURRENCY` (proposed 16) is likewise a starting default.
- No app-level metrics/observability adapter is planned — the k6 →
  InfluxDB → Grafana pipeline already provided is the load-test reporting
  path; nothing in this design pushes custom metrics.
- `yourApp` is not added to `docker-compose.yaml` — per the README's own
  test flow, only `simulado`, `influxdb`, and `grafana` run via compose;
  the app itself runs on the host (`mvn spring-boot:run` or the packaged
  jar), consistent with how the k6 script reaches it via
  `host.docker.internal:5000`.
