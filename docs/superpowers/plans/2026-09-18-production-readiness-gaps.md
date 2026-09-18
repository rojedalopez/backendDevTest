# Production Readiness Gaps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close four industry-standard/Java-best-practices gaps in the already-implemented similar-products service: no Spring Boot Actuator, no Resilience4j event visibility, non-standard (empty-body) error responses, and no input validation on `productId`.

**Architecture:** Four independent, additive changes layered onto the existing hexagonal service — no existing endpoint behavior changes for valid requests, only new observability (Actuator endpoints, resilience event logs) and better-shaped failure responses (RFC 7807 `ProblemDetail`, 400 on invalid input).

**Tech Stack:** Spring Boot Actuator, SLF4J/Logback (already transitively present via `spring-boot-starter-webflux`), Spring's built-in `ProblemDetail` (RFC 7807), Jakarta Bean Validation (`spring-boot-starter-validation`).

**Spec:** `docs/superpowers/specs/2026-09-17-similar-products-service-design.md` (original service spec — this plan extends it, does not replace it). Requirements for this plan itself were agreed in chat, not written to a separate spec file, since the scope is bounded and well understood.

## Global Constraints

- Every new/modified production class must be exercised by a test that runs **in the same Maven module** — JaCoCo coverage is scoped per-module; a test in a different module gives that module's own bundle zero credit (this bit Task 4 of the original plan with `WebClientConfig`, and bites `GlobalExceptionHandler`'s validation handler here too — see Task 4).
- Every module must maintain ≥95% line coverage and pass Checkstyle (zero error-severity violations, `checkstyle.xml` at repo root), both enforced by `mvn verify` — unchanged from the original project rule.
- No new dependency needs an explicit `<version>` — every starter added here (`spring-boot-starter-actuator`, `spring-boot-starter-validation`) is managed transitively via the root `pom.xml`'s `spring-boot-starter-parent` parent, matching every existing dependency in this project.
- `@Size(max = 64)` on `productId` is a method-contract constraint, not environment config — it stays inline on the annotation, not externalized to `application.yml` (unlike timeouts/URLs/thresholds, which the original spec's rule 4 covers).
- A 500 (`handleUnexpected`) response's `ProblemDetail.detail` must always be the fixed string `"An unexpected error occurred."` — never the raw exception message, to avoid leaking internals to a client.
- Package-private visibility for infra classes (existing convention) continues to apply to any new class.

---

## File Structure

```
application/pom.xml                                                          [modify] add actuator
application/src/main/resources/application.yml                              [modify] add management.endpoints config
application/src/test/java/com/inditex/similarproducts/ActuatorHealthTest.java [create]
application/src/test/java/com/inditex/similarproducts/SimilarProductsValidationTest.java [create]

infrastructure/out/rest/src/main/java/.../SimilarProductIdsAdapter.java     [modify] add event logging
infrastructure/out/rest/src/main/java/.../ProductDetailAdapter.java         [modify] add event logging
infrastructure/out/rest/src/test/java/.../SimilarProductIdsAdapterTest.java [modify] add logging test
infrastructure/out/rest/src/test/java/.../ProductDetailAdapterTest.java     [modify] add timeout + logging tests

infrastructure/in/rest/pom.xml                                              [modify] add validation starter
infrastructure/in/rest/src/main/java/.../GlobalExceptionHandler.java        [modify] ProblemDetail + validation handler
infrastructure/in/rest/src/main/java/.../SimilarProductsController.java     [modify] @Validated + @Size
infrastructure/in/rest/src/test/java/.../SimilarProductsControllerTest.java [modify] ProblemDetail assertions + direct handler test
```

---

### Task 1: Spring Boot Actuator

**Files:**
- Modify: `application/pom.xml`
- Modify: `application/src/main/resources/application.yml`
- Test: `application/src/test/java/com/inditex/similarproducts/ActuatorHealthTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `GET /actuator/health` returning `{"status":"UP"}` when the app is healthy — used by nothing else in this plan, but this is the endpoint a container orchestrator's liveness/readiness probe would hit.

- [ ] **Step 1: Write the failing test**

`application/src/test/java/com/inditex/similarproducts/ActuatorHealthTest.java`:

```java
package com.inditex.similarproducts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = "server.port=0")
class ActuatorHealthTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void healthEndpointReportsUp() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl application -am test -Dtest=ActuatorHealthTest`
Expected: FAIL — `/actuator/health` returns 404, since Actuator isn't on the classpath yet.

- [ ] **Step 3: Add the dependency and configuration**

In `application/pom.xml`, add inside `<dependencies>` (alongside the existing `spring-boot-starter-webflux` entry):

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
```

In `application/src/main/resources/application.yml`, add a new top-level `management` block (place it after the existing `server:` block, before `mocks:`):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

Leave `management.endpoint.health.show-details` unset — the Spring Boot default (`never`) is the right choice here since this service has no authentication layer; it keeps the health response to just `{"status":"UP"}` with no internal component details exposed.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl application -am test -Dtest=ActuatorHealthTest`
Expected: `BUILD SUCCESS`, 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add application/pom.xml application/src/main/resources/application.yml \
  application/src/test/java/com/inditex/similarproducts/ActuatorHealthTest.java
git commit -m "$(cat <<'EOF'
Add Spring Boot Actuator (health, info, metrics)

Standard production-readiness expectation for a Spring Boot service
headed toward any container/orchestrator environment. show-details
stays at the secure default (never) since there's no auth layer yet.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018R3CGLaKwwmZdCHknEGUjL
EOF
)"
```

---

### Task 2: Resilience4j Event Logging

**Files:**
- Modify: `infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/SimilarProductIdsAdapter.java`
- Modify: `infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/ProductDetailAdapter.java`
- Modify: `infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/SimilarProductIdsAdapterTest.java`
- Modify: `infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/ProductDetailAdapterTest.java`

**Interfaces:**
- Consumes: `TimeLimiter`/`CircuitBreaker` instances already held by both adapters (unchanged fields).
- Produces: nothing new consumed elsewhere — this is pure observability, logging via SLF4J's `Logger` (`org.slf4j.LoggerFactory.getLogger(<AdapterClass>.class)`).

- [ ] **Step 1: Write the failing tests**

Add to `infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/SimilarProductIdsAdapterTest.java` — first add these imports (alongside the existing ones):

```java
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
```

Then add this test method inside the class (after `circuitBreakerOpensAfterRepeatedFailures`):

```java
    @Test
    void logsCircuitBreakerStateTransitionWhenItOpens() {
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(SimilarProductIdsAdapter.class);
        logAppender.start();
        logger.addAppender(logAppender);

        wireMockServer.stubFor(get(urlEqualTo("/product/8/similarids"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 10; i++) {
            StepVerifier.create(adapter.findSimilarProductIds("8")).expectError().verify();
        }

        boolean transitionLogged = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.INFO
                        && event.getFormattedMessage().contains("CLOSED to OPEN"));
        assertThat(transitionLogged).isTrue();

        logger.detachAppender(logAppender);
    }
```

Add to `infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/ProductDetailAdapterTest.java` — add these imports (alongside the existing ones):

```java
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.TimeoutException;
```

Then add these two test methods (after `circuitBreakerOpensAfterRepeatedFailures`):

```java
    @Test
    void propagatesErrorWhenCallTimesOut() {
        wireMockServer.stubFor(get(urlEqualTo("/product/9"))
                .willReturn(aResponse()
                        .withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"9\",\"name\":\"Coat\",\"price\":59.99,\"availability\":true}")));

        StepVerifier.create(adapter.findProductDetail("9"))
                .expectError(TimeoutException.class)
                .verify();
    }

    @Test
    void logsCircuitBreakerStateTransitionWhenItOpens() {
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ProductDetailAdapter.class);
        logAppender.start();
        logger.addAppender(logAppender);

        wireMockServer.stubFor(get(urlEqualTo("/product/10"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 10; i++) {
            StepVerifier.create(adapter.findProductDetail("10")).expectError().verify();
        }

        boolean transitionLogged = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.INFO
                        && event.getFormattedMessage().contains("CLOSED to OPEN"));
        assertThat(transitionLogged).isTrue();

        logger.detachAppender(logAppender);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl infrastructure/out/rest -am test`
Expected: the two new `logsCircuitBreakerStateTransitionWhenItOpens` tests FAIL (no log line ever appears — nothing logs yet). The new `propagatesErrorWhenCallTimesOut` test in `ProductDetailAdapterTest` should PASS already (the timeout behavior itself already works, this test is new coverage for existing behavior, not testing new behavior) — that's fine, it's the two logging tests that must fail here.

- [ ] **Step 3: Add event logging to both adapters**

In `infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/SimilarProductIdsAdapter.java`, add these imports (alongside the existing ones, in alphabetical order with the others):

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Add a logger field (after `ID_LIST_TYPE`):

```java
    private static final Logger log = LoggerFactory.getLogger(SimilarProductIdsAdapter.class);
```

At the end of the constructor body (after the existing three field assignments), add:

```java
        this.circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.info("Circuit breaker '{}' transitioned from {} to {}",
                        INSTANCE_NAME, event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
        this.timeLimiter.getEventPublisher()
                .onTimeout(event -> log.warn("TimeLimiter '{}' timed out", INSTANCE_NAME));
```

In `infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/ProductDetailAdapter.java`, add the same two imports, the same logger field (`ProductDetailAdapter.class` as the argument), and the same two event-publisher registrations at the end of the constructor (after the `this.cache = ...` assignment), with the identical code (the `INSTANCE_NAME` constant already differs per-class, so the log messages are automatically adapter-specific).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl infrastructure/out/rest -am test`
Expected: `BUILD SUCCESS`, all tests pass (8 existing + 2 new circuit-breaker-open tests already existed from the final review + this task's 3 new tests = 13 tests total in this module).

- [ ] **Step 5: Commit**

```bash
git add infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/SimilarProductIdsAdapter.java \
  infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/ProductDetailAdapter.java \
  infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/SimilarProductIdsAdapterTest.java \
  infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/ProductDetailAdapterTest.java
git commit -m "$(cat <<'EOF'
Log Resilience4j circuit breaker and timeout events

Both adapters were a black box operationally — nothing indicated when
a breaker opened/closed or a call timed out. Wires SLF4J logging into
each adapter's existing TimeLimiter/CircuitBreaker event publishers.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018R3CGLaKwwmZdCHknEGUjL
EOF
)"
```

---

### Task 3: RFC 7807 Error Responses

**Files:**
- Modify: `infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/GlobalExceptionHandler.java`
- Modify: `infrastructure/in/rest/src/test/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsControllerTest.java`

**Interfaces:**
- Consumes: `ProductNotFoundException` (from Task 2 of the original plan).
- Produces: `GlobalExceptionHandler.handleNotFound(ProductNotFoundException): ProblemDetail` and `GlobalExceptionHandler.handleUnexpected(Exception): ProblemDetail` — Task 4 adds a third handler to this same class, consuming the pattern established here.

- [ ] **Step 1: Write the failing test**

In `infrastructure/in/rest/src/test/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsControllerTest.java`, replace the bodies of the two existing failure tests:

```java
    @Test
    void returnsNotFoundWhenBaseProductMissing() {
        when(useCase.getSimilarProducts("404")).thenReturn(Mono.error(new ProductNotFoundException("404")));

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
        when(useCase.getSimilarProducts("1")).thenReturn(Mono.error(new RuntimeException("boom")));

        webTestClient.get().uri("/product/1/similar")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(500)
                .jsonPath("$.detail").isEqualTo("An unexpected error occurred.");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl infrastructure/in/rest -am test`
Expected: both tests FAIL — the current handler returns an empty body with no `Content-Type: application/problem+json`, so the `jsonPath` assertions fail (empty body has nothing to match).

- [ ] **Step 3: Rewrite the exception handler**

Replace the full content of `infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/GlobalExceptionHandler.java`:

```java
package com.inditex.similarproducts.infrastructure.in.rest;

import com.inditex.similarproducts.model.ProductNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail handleNotFound(ProductNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl infrastructure/in/rest -am test`
Expected: `BUILD SUCCESS`, all 4 existing tests pass, including the two strengthened ones.

- [ ] **Step 5: Commit**

```bash
git add infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/GlobalExceptionHandler.java \
  infrastructure/in/rest/src/test/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsControllerTest.java
git commit -m "$(cat <<'EOF'
Return RFC 7807 ProblemDetail instead of empty error bodies

404/500 responses previously had empty bodies with no machine-readable
error info. Spring's built-in ProblemDetail gives clients a structured
application/problem+json body; the 500 detail is always a fixed
generic string so internal exception messages never leak to clients.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018R3CGLaKwwmZdCHknEGUjL
EOF
)"
```

---

### Task 4: Input Validation on `productId`

**Files:**
- Modify: `infrastructure/in/rest/pom.xml`
- Modify: `infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsController.java`
- Modify: `infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/GlobalExceptionHandler.java`
- Modify: `infrastructure/in/rest/src/test/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsControllerTest.java`
- Test: `application/src/test/java/com/inditex/similarproducts/SimilarProductsValidationTest.java`

**Interfaces:**
- Consumes: `ProblemDetail`-returning `GlobalExceptionHandler` pattern from Task 3.
- Produces: `GlobalExceptionHandler.handleValidationFailure(ConstraintViolationException): ProblemDetail` — nothing later in this plan consumes it, but this completes the handler class.

**Important — read before starting:** `@Validated`-driven method-parameter validation (the `@Size` constraint on `productId`) only actually triggers through a real Spring AOP proxy, which requires a full `ApplicationContext` — `WebTestClient.bindToController(...)` (used by the existing `SimilarProductsControllerTest`) does **not** build one, so that test file can never exercise the validation trigger itself, only whether `GlobalExceptionHandler.handleValidationFailure` maps a `ConstraintViolationException` correctly once one exists. The end-to-end trigger (a real over-length request actually producing a 400) can only be proven where the full Spring context exists: the `application` module. This is exactly the "test must run in the same module as the code it covers" constraint from Global Constraints — `handleValidationFailure` lives in `infrastructure/in/rest`, so it needs its own direct, in-module test in addition to the full-stack proof in `application`.

- [ ] **Step 1: Write the failing tests**

In `infrastructure/in/rest/src/test/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsControllerTest.java`, add these imports (alongside the existing ones):

```java
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.springframework.http.ProblemDetail;
```

Add this test method at the end of the class:

```java
    @Test
    void mapsConstraintViolationToBadRequestProblemDetail() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ConstraintViolationException exception =
                new ConstraintViolationException("productId size must be <= 64", Set.of());

        ProblemDetail problemDetail = handler.handleValidationFailure(exception);

        assertThat(problemDetail.getStatus()).isEqualTo(400);
        assertThat(problemDetail.getDetail()).isEqualTo("productId size must be <= 64");
    }
```

`application/src/test/java/com/inditex/similarproducts/SimilarProductsValidationTest.java`:

```java
package com.inditex.similarproducts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = "server.port=0")
class SimilarProductsValidationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void rejectsOverlyLongProductId() {
        String tooLong = "x".repeat(65);

        webTestClient.get().uri("/product/{productId}/similar", tooLong)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl infrastructure/in/rest -am test`
Expected: FAIL to compile — `GlobalExceptionHandler.handleValidationFailure` doesn't exist yet.

Run: `mvn -q -pl application -am test -Dtest=SimilarProductsValidationTest`
Expected: FAIL — no `@Size` constraint exists yet, so the over-length `productId` is accepted and the request proceeds to the use case, which returns something other than 400 (it'll actually hang/error differently since no mock backs "xxxx...x", but regardless it won't be a clean 400 — the point is the test fails, confirming validation isn't wired yet).

- [ ] **Step 3: Add the validation dependency, constraint, and handler**

In `infrastructure/in/rest/pom.xml`, add inside `<dependencies>` (alongside `spring-boot-starter-webflux`):

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
```

In `infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsController.java`, add these imports (alongside the existing ones):

```java
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
```

Add `@Validated` on the class, above `@RestController`:

```java
@RestController
@Validated
class SimilarProductsController {
```

Change the `getSimilar` method's parameter:

```java
    @GetMapping("/product/{productId}/similar")
    Mono<ResponseEntity<List<ProductDetail>>> getSimilar(
            @PathVariable @Size(max = 64) String productId) {
        return useCase.getSimilarProducts(productId)
                .map(SimilarProductsController::toResponse);
    }
```

In `infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/GlobalExceptionHandler.java`, add this import (alongside the existing ones):

```java
import jakarta.validation.ConstraintViolationException;
```

Add this handler method (between `handleNotFound` and `handleUnexpected` — order matters for readability, not behavior, since Spring dispatches by exception type specificity, not declaration order):

```java
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleValidationFailure(ConstraintViolationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl infrastructure/in/rest -am test`
Expected: `BUILD SUCCESS`, all 5 tests pass (4 existing + the new direct handler test).

Run: `mvn -q -pl application -am test`
Expected: `BUILD SUCCESS`, all tests pass (existing `ApplicationContextLoadsTest` + `ActuatorHealthTest` from Task 1 + the new `SimilarProductsValidationTest`).

- [ ] **Step 5: Commit**

```bash
git add infrastructure/in/rest/pom.xml \
  infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsController.java \
  infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/GlobalExceptionHandler.java \
  infrastructure/in/rest/src/test/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsControllerTest.java \
  application/src/test/java/com/inditex/similarproducts/SimilarProductsValidationTest.java
git commit -m "$(cat <<'EOF'
Validate productId length, return 400 ProblemDetail on failure

Any string previously passed straight through to the downstream
lookup with no boundary check. Caps productId at 64 characters via
Bean Validation, mapped to the same RFC 7807 ProblemDetail shape as
the other error responses. The end-to-end trigger only proves out
through a real Spring context (infrastructure/in/rest's own
WebTestClient.bindToController tests can't build the AOP validation
proxy), so this adds a direct handler-level test in-module plus a
full-context test in application.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018R3CGLaKwwmZdCHknEGUjL
EOF
)"
```

---

### Task 5: Full-Reactor Verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full build gate**

Run: `mvn -q -f pom.xml clean verify`
Expected: `BUILD SUCCESS` across all 5 modules — every module still at ≥95% line coverage (check `target/site/jacoco/jacoco.xml` per module if the quiet run gives no detail), 0 Checkstyle error-severity violations.

- [ ] **Step 2: Manually confirm the four gaps are actually closed**

Start the app (`java -jar application/target/application.jar`) with the mocks up (`docker-compose up -d simulado influxdb grafana`), then:

```bash
curl -s http://localhost:5000/actuator/health
# expect: {"status":"UP"}

curl -i http://localhost:5000/product/999/similar
# expect: 404, Content-Type: application/problem+json, body has "status":404 and a "detail" field

curl -i "http://localhost:5000/product/$(python3 -c 'print("x"*65)')/similar"
# expect: 400, Content-Type: application/problem+json

tail -f <app log output> # trigger a few /product/6/similar or /product/5/similar calls repeatedly and confirm
# a "Circuit breaker ... transitioned from CLOSED to OPEN" line appears once enough failures accumulate
```

Stop the app and tear down docker-compose afterward.

- [ ] **Step 3: No commit for this task** — it's verification only. If Step 1 or Step 2 reveals a problem, fix it in the specific task's own commit (amend via a new commit in that area, not here).
