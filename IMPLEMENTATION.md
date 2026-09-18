# Implementation Summary

Concise summary of what was built, for a reviewer. For the full backstory,
see the note on `docs/` below.

## How to build and run

`mvn clean verify` (from repo root) builds all modules, runs the full test
suite, and enforces the coverage (JaCoCo, 95% line coverage per module) and
lint (Checkstyle) quality gates — the build fails if either gate is not met.

`java -jar application/target/application.jar` runs the service on port
5000.

**Note (macOS):** port 5000 may already be bound by the system's AirPlay
Receiver (Control Center). If startup fails with "address already in use",
either disable AirPlay Receiver in System Settings > General > AirDrop &
Handoff, or run with `--server.port=<other-port>` and adjust
`shared/k6/test.js`'s host URL accordingly for local load testing.

## Architecture

The project is a hexagonal (ports & adapters) layout split across five Maven
modules: `domain/model` (the core types and port interfaces, no framework
dependencies), `domain/usecase` (the `GetSimilarProductsUseCase` orchestrating
the business logic against those ports), `infrastructure/in/rest` (the
inbound REST controller exposing `GET /product/{productId}/similar`),
`infrastructure/out/rest` (outbound WebClient-based adapters calling the
mocked downstream `similarids`/`product` endpoints), and `application` (the
Spring Boot bootstrap wiring everything together). Dependencies point inward
only — both infrastructure modules depend on `domain/usecase` and
`domain/model`, and `domain` never depends on infrastructure or Spring.

## Key design decisions

- **WebFlux + Resilience4j** (`TimeLimiter` + `CircuitBreaker`, one instance
  pair per downstream call) handles the core problem of concurrent,
  potentially-slow, potentially-failing downstream calls: similar-ids lookup
  fans out into up to 16 concurrent product-detail lookups per request, each
  individually time-limited and circuit-broken.
- A **success-only Caffeine cache** (`AsyncCache`, TTL + max size configured
  via `application.yml`) fronts product-detail lookups. Failures are never
  cached, and the productDetailService circuit breaker is configured to
  ignore 404s, so a legitimately-missing product doesn't skew the breaker's
  view of downstream health.
- `GET /product/{productId}/similar` returns **206 Partial Content** (not
  just 200/404) when some — or all — of the similar products' details
  couldn't be resolved but the base product itself was found. This is a
  deliberate, documented extension beyond `similarProducts.yaml`'s literal
  200/404 contract; see
  `docs/superpowers/specs/2026-09-17-similar-products-service-design.md`
  §6 for the full rationale.
- Similar products are returned in the same order as the similar-ids list
  from the mock (ordered by similarity, per the contract), using
  `flatMapSequential` with bounded concurrency rather than plain `flatMap`,
  which would emit in completion order instead.

## Operational endpoints and error contract

- `GET /actuator/health`, `/actuator/info`, `/actuator/metrics` are exposed (Spring Boot Actuator) for container/orchestrator health checks and basic operational visibility. `show-details` stays at the secure default (`never`) since there's no authentication layer.
- Both outbound adapters log Resilience4j circuit-breaker state transitions and TimeLimiter timeouts via SLF4J, so breaker trips and timeouts are now visible in application logs rather than silent.
- Error responses use RFC 7807 (`application/problem+json`, Spring's built-in `ProblemDetail`) instead of empty bodies: 404 for an unknown base product, 400 for an invalid request (currently: `productId` over 64 characters), 500 for anything unexpected (with a fixed, non-leaking detail message — the real exception is logged server-side only).
- This adds Micrometer (via Actuator) to the stack, which the original design spec explicitly deferred ("no app-level metrics/observability adapter is planned") — that position changed for this follow-up work; see `docs/superpowers/specs/2026-09-17-similar-products-service-design.md` §10 for the original reasoning this supersedes.

## Note on the `docs/` folder

`docs/superpowers/specs/` holds the design rationale behind the choices
above (including the 206 decision and the caching/resilience trade-offs).
`docs/superpowers/plans/` holds the step-by-step implementation plan this
service was built from, kept for provenance. Neither is required reading
to understand the final result — this file is.
