# Similar Products Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `yourApp` — a Spring Boot WebFlux service on port 5000 exposing `GET /product/{productId}/similar`, composing the two existing mock APIs with Resilience4j timeouts/circuit-breakers, a success-only product-detail cache, and a hexagonal module layout enforced by a 95%-coverage + zero-violation Checkstyle build gate.

**Architecture:** Ports-and-adapters across five Maven modules (`domain/model`, `domain/usecase`, `infrastructure/in/rest`, `infrastructure/out/rest`, `application`). The reactive request path is: controller → use case → `SimilarProductIdsPort` (fails fast on 404) → fan-out to `ProductDetailPort` per similar id (individual failures swallowed, cached on success) → 200/206 response.

**Tech Stack:** Java 24, Spring Boot 3.5.4 (WebFlux), Resilience4j 2.3.0 (TimeLimiter + CircuitBreaker via reactor operators), Caffeine 3.1.8 (async cache), JUnit 5 + Mockito + AssertJ + reactor-test + WireMock (tests), JaCoCo 0.8.12 + Checkstyle (Google style) as build-enforced gates.

**Spec:** `docs/superpowers/specs/2026-09-17-similar-products-service-design.md`

## Global Constraints

- Java 24 everywhere (`java.version=24`); app listens on port 5000 (`similarProducts.yaml` contract).
- `domain/model` and `domain/usecase` have zero Spring/WebClient/Resilience4j dependencies (spec §8 rule 1).
- No raw `WebClient` calls outside `infrastructure/out/rest`'s two adapters (spec §8 rule 2).
- No `.block()` outside test code (spec §8 rule 3).
- All tunables (base URL, timeouts, circuit-breaker thresholds, pool size, cache TTL/size) live in `application.yml`, never hardcoded (spec §8 rule 4).
- A single failed similar-product detail lookup never fails the whole request; only the base product's 404 (or an unexpected error on that call) does (spec §8 rule 7).
- Every module: ≥95% line coverage (JaCoCo) and zero **error**-severity Checkstyle (Google style) violations, both enforced by `mvn verify` (spec §8 rule 8). `application` module's `Application.class` is excluded from the coverage count (spec §7).
- Caching is scoped to `ProductDetailAdapter` only, success-only, never leaks into a port contract or into `domain/usecase` (spec §8 rule 9).
- `206 PARTIAL_CONTENT` for partial results is a deliberate, agreed extension beyond `similarProducts.yaml` (spec §6).
- `yourApp` is not added to `docker-compose.yaml`; it runs on the host, reachable at `host.docker.internal:5000` from the k6 container (spec §10).

---

## File Structure

```
pom.xml                                                          [create] root aggregator

domain/model/pom.xml                                             [modify]
domain/model/src/main/java/ports/out/SimilarProductsService.java [delete] old stub
domain/model/src/main/java/com/inditex/similarproducts/model/ProductDetail.java                 [create]
domain/model/src/main/java/com/inditex/similarproducts/model/ProductNotFoundException.java       [create]
domain/model/src/main/java/com/inditex/similarproducts/model/port/SimilarProductIdsPort.java     [create]
domain/model/src/main/java/com/inditex/similarproducts/model/port/ProductDetailPort.java         [create]
domain/model/src/test/java/com/inditex/similarproducts/model/ProductDetailTest.java              [create]
domain/model/src/test/java/com/inditex/similarproducts/model/ProductNotFoundExceptionTest.java   [create]

domain/usecase/pom.xml                                           [modify]
domain/usecase/src/main/java/com/inditex/similarproducts/usecase/SimilarProductsResult.java      [create]
domain/usecase/src/main/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCase.java  [create]
domain/usecase/src/test/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCaseTest.java [create]

infrastructure/in/rest/pom.xml                                   [modify]
infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsController.java [create]
infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/GlobalExceptionHandler.java    [create]
infrastructure/in/rest/src/test/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsControllerTest.java [create]

infrastructure/out/grafana/  -->  infrastructure/out/rest/        [rename, git mv]
infrastructure/out/rest/pom.xml                                  [modify, post-rename]
infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/MocksProperties.java      [create]
infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/WebClientConfig.java     [create]
infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/SimilarProductIdsAdapter.java [create]
infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/ProductDetailAdapter.java     [create]
infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/WebClientConfigTest.java          [create]
infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/SimilarProductIdsAdapterTest.java [create]
infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/ProductDetailAdapterTest.java     [create]

application/pom.xml                                               [modify]
application/src/main/java/com/inditex/similarproducts/Application.java     [create]
application/src/main/java/com/inditex/similarproducts/UseCaseConfig.java   [create]
application/src/main/resources/application.yml                            [create]
application/src/test/java/com/inditex/similarproducts/ApplicationContextLoadsTest.java [create]
```

---

### Task 1: Project & Build Setup

**Files:**
- Create: `pom.xml` (root aggregator)
- Modify: `domain/model/pom.xml`, `domain/usecase/pom.xml`, `infrastructure/in/rest/pom.xml`, `application/pom.xml`
- Rename (git mv): `infrastructure/out/grafana/` → `infrastructure/out/rest/`
- Modify: `infrastructure/out/rest/pom.xml` (post-rename)

**Interfaces:**
- Consumes: nothing (first task).
- Produces: a resolvable, compiling 5-module Maven reactor rooted at `com.inditex:similar-products:1.0-SNAPSHOT`, with every child's final dependency set already in place so later tasks only add source files. Module artifactIds: `model`, `usecase`, `in-rest`, `out-rest`, `application`.

- [ ] **Step 1: Rename the outbound module directory**

```bash
git mv infrastructure/out/grafana infrastructure/out/rest
```

- [ ] **Step 2: Create the root aggregator `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.4</version>
        <relativePath/>
    </parent>

    <groupId>com.inditex</groupId>
    <artifactId>similar-products</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>domain/model</module>
        <module>domain/usecase</module>
        <module>infrastructure/in/rest</module>
        <module>infrastructure/out/rest</module>
        <module>application</module>
    </modules>

    <properties>
        <java.version>24</java.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <resilience4j.version>2.3.0</resilience4j.version>
        <caffeine.version>3.1.8</caffeine.version>
        <wiremock.version>3.9.2</wiremock.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.github.resilience4j</groupId>
                <artifactId>resilience4j-bom</artifactId>
                <version>${resilience4j.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.github.ben-manes.caffeine</groupId>
                <artifactId>caffeine</artifactId>
                <version>${caffeine.version}</version>
            </dependency>
            <dependency>
                <groupId>org.wiremock</groupId>
                <artifactId>wiremock-standalone</artifactId>
                <version>${wiremock.version}</version>
                <scope>test</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>0.8.12</version>
                <executions>
                    <execution>
                        <id>prepare-agent</id>
                        <goals>
                            <goal>prepare-agent</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>report</id>
                        <phase>test</phase>
                        <goals>
                            <goal>report</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>check</id>
                        <phase>verify</phase>
                        <goals>
                            <goal>check</goal>
                        </goals>
                        <configuration>
                            <rules>
                                <rule>
                                    <element>BUNDLE</element>
                                    <limits>
                                        <limit>
                                            <counter>LINE</counter>
                                            <value>COVEREDRATIO</value>
                                            <minimum>0.95</minimum>
                                        </limit>
                                    </limits>
                                </rule>
                            </rules>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-checkstyle-plugin</artifactId>
                <version>3.6.0</version>
                <configuration>
                    <configLocation>google_checks.xml</configLocation>
                    <consoleOutput>true</consoleOutput>
                    <failOnViolation>true</failOnViolation>
                    <violationSeverity>error</violationSeverity>
                </configuration>
                <executions>
                    <execution>
                        <id>checkstyle-check</id>
                        <phase>verify</phase>
                        <goals>
                            <goal>check</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

`violationSeverity` is set to `error` (not Google's default `warn`) so the gate fails on real defects (unused imports, naming, line-length-over-100, etc.) without forcing Javadoc on every method, which `google_checks.xml` treats as advisory. Keep source lines under 100 characters throughout this plan's code (Google style, error-severity `LineLength` check).

- [ ] **Step 3: Rewrite `domain/model/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.inditex</groupId>
        <artifactId>similar-products</artifactId>
        <version>1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>model</artifactId>

    <dependencies>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: Rewrite `domain/usecase/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.inditex</groupId>
        <artifactId>similar-products</artifactId>
        <version>1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>usecase</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.inditex</groupId>
            <artifactId>model</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 5: Rewrite `infrastructure/in/rest/pom.xml`** (artifactId changes from `rest` to `in-rest`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.inditex</groupId>
        <artifactId>similar-products</artifactId>
        <version>1.0-SNAPSHOT</version>
        <relativePath>../../../pom.xml</relativePath>
    </parent>

    <artifactId>in-rest</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.inditex</groupId>
            <artifactId>usecase</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.inditex</groupId>
            <artifactId>model</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 6: Rewrite `infrastructure/out/rest/pom.xml`** (artifactId changes from `grafana` to `out-rest`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.inditex</groupId>
        <artifactId>similar-products</artifactId>
        <version>1.0-SNAPSHOT</version>
        <relativePath>../../../pom.xml</relativePath>
    </parent>

    <artifactId>out-rest</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.inditex</groupId>
            <artifactId>model</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot3</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-reactor</artifactId>
        </dependency>
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 7: Rewrite `application/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.inditex</groupId>
        <artifactId>similar-products</artifactId>
        <version>1.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>application</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>com.inditex</groupId>
            <artifactId>model</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.inditex</groupId>
            <artifactId>usecase</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.inditex</groupId>
            <artifactId>in-rest</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.inditex</groupId>
            <artifactId>out-rest</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>application</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>check</id>
                        <configuration>
                            <excludes>
                                <exclude>**/Application.class</exclude>
                            </excludes>
                        </configuration>
                    </execution>
                    <execution>
                        <id>report</id>
                        <configuration>
                            <excludes>
                                <exclude>**/Application.class</exclude>
                            </excludes>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 8: Verify the reactor resolves and compiles**

Run: `mvn -q -f pom.xml clean compile`
Expected: `BUILD SUCCESS`, all 6 modules (root + 5 children) build in dependency order. The pre-existing `ports.out.SimilarProductsService` stub in `domain/model` still compiles untouched at this point.

- [ ] **Step 9: Commit**

```bash
git add pom.xml domain/model/pom.xml domain/usecase/pom.xml \
  infrastructure/in/rest/pom.xml infrastructure/out/rest infrastructure/out/grafana \
  application/pom.xml
git commit -m "$(cat <<'EOF'
Set up multi-module Maven build skeleton

Adds a root aggregator pom (Spring Boot 3.5.4 parent, Resilience4j/
Caffeine/WireMock dependency management, JaCoCo 95% + Checkstyle
error-severity gates on verify) and rewrites every child pom with its
final dependency set. Renames infrastructure/out/grafana to
infrastructure/out/rest since it's the mocks HTTP client, not a
metrics module.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Domain Model

**Files:**
- Delete: `domain/model/src/main/java/ports/out/SimilarProductsService.java`
- Create: `domain/model/src/main/java/com/inditex/similarproducts/model/ProductDetail.java`
- Create: `domain/model/src/main/java/com/inditex/similarproducts/model/ProductNotFoundException.java`
- Create: `domain/model/src/main/java/com/inditex/similarproducts/model/port/SimilarProductIdsPort.java`
- Create: `domain/model/src/main/java/com/inditex/similarproducts/model/port/ProductDetailPort.java`
- Test: `domain/model/src/test/java/com/inditex/similarproducts/model/ProductDetailTest.java`
- Test: `domain/model/src/test/java/com/inditex/similarproducts/model/ProductNotFoundExceptionTest.java`

**Interfaces:**
- Consumes: nothing beyond `reactor-core`'s `Mono` (from Task 1's pom).
- Produces (used by every later task):
  - `com.inditex.similarproducts.model.ProductDetail(String id, String name, BigDecimal price, boolean availability)`
  - `com.inditex.similarproducts.model.ProductNotFoundException(String productId)` — unchecked
  - `com.inditex.similarproducts.model.port.SimilarProductIdsPort#findSimilarProductIds(String productId): Mono<List<String>>`
  - `com.inditex.similarproducts.model.port.ProductDetailPort#findProductDetail(String productId): Mono<ProductDetail>`

- [ ] **Step 1: Write the failing tests**

`domain/model/src/test/java/com/inditex/similarproducts/model/ProductDetailTest.java`:

```java
package com.inditex.similarproducts.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductDetailTest {

    @Test
    void exposesAllFields() {
        ProductDetail detail = new ProductDetail("1", "Shirt", BigDecimal.valueOf(9.99), true);

        assertThat(detail.id()).isEqualTo("1");
        assertThat(detail.name()).isEqualTo("Shirt");
        assertThat(detail.price()).isEqualByComparingTo("9.99");
        assertThat(detail.availability()).isTrue();
    }

    @Test
    void equalRecordsAreEqual() {
        ProductDetail first = new ProductDetail("1", "Shirt", BigDecimal.valueOf(9.99), true);
        ProductDetail second = new ProductDetail("1", "Shirt", BigDecimal.valueOf(9.99), true);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first.toString()).contains("Shirt");
    }
}
```

`domain/model/src/test/java/com/inditex/similarproducts/model/ProductNotFoundExceptionTest.java`:

```java
package com.inditex.similarproducts.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductNotFoundExceptionTest {

    @Test
    void messageContainsProductId() {
        ProductNotFoundException exception = new ProductNotFoundException("42");

        assertThat(exception.getMessage()).contains("42");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl domain/model -am test`
Expected: FAIL to compile — `ProductDetail`/`ProductNotFoundException` don't exist yet.

- [ ] **Step 3: Delete the old stub and write the domain classes**

```bash
git rm domain/model/src/main/java/ports/out/SimilarProductsService.java
```

`domain/model/src/main/java/com/inditex/similarproducts/model/ProductDetail.java`:

```java
package com.inditex.similarproducts.model;

import java.math.BigDecimal;

public record ProductDetail(String id, String name, BigDecimal price, boolean availability) {
}
```

`domain/model/src/main/java/com/inditex/similarproducts/model/ProductNotFoundException.java`:

```java
package com.inditex.similarproducts.model;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String productId) {
        super("Product not found: " + productId);
    }
}
```

`domain/model/src/main/java/com/inditex/similarproducts/model/port/SimilarProductIdsPort.java`:

```java
package com.inditex.similarproducts.model.port;

import java.util.List;
import reactor.core.publisher.Mono;

public interface SimilarProductIdsPort {

    Mono<List<String>> findSimilarProductIds(String productId);
}
```

`domain/model/src/main/java/com/inditex/similarproducts/model/port/ProductDetailPort.java`:

```java
package com.inditex.similarproducts.model.port;

import com.inditex.similarproducts.model.ProductDetail;
import reactor.core.publisher.Mono;

public interface ProductDetailPort {

    Mono<ProductDetail> findProductDetail(String productId);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl domain/model -am test`
Expected: `BUILD SUCCESS`, 3 tests run, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add domain/model
git commit -m "$(cat <<'EOF'
Add domain model and ports for similar products

Replaces the empty SimilarProductsService stub with ProductDetail,
ProductNotFoundException, and two single-purpose ports
(SimilarProductIdsPort, ProductDetailPort) matching the two distinct
downstream calls.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Orchestration Use Case

**Files:**
- Create: `domain/usecase/src/main/java/com/inditex/similarproducts/usecase/SimilarProductsResult.java`
- Create: `domain/usecase/src/main/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCase.java`
- Test: `domain/usecase/src/test/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCaseTest.java`

**Interfaces:**
- Consumes: `SimilarProductIdsPort`, `ProductDetailPort`, `ProductDetail`, `ProductNotFoundException` (Task 2).
- Produces:
  - `com.inditex.similarproducts.usecase.SimilarProductsResult(List<ProductDetail> products, boolean partial)`
  - `com.inditex.similarproducts.usecase.GetSimilarProductsUseCase(SimilarProductIdsPort, ProductDetailPort)` — constructor
  - `GetSimilarProductsUseCase#getSimilarProducts(String productId): Mono<SimilarProductsResult>`

- [ ] **Step 1: Write the failing test**

`domain/usecase/src/test/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCaseTest.java`:

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
        when(productDetailPort.findProductDetail("2")).thenReturn(Mono.just(detail("2")));
        when(productDetailPort.findProductDetail("3")).thenReturn(Mono.just(detail("3")));

        StepVerifier.create(useCase.getSimilarProducts("1"))
                .assertNext(result -> {
                    assertThat(result.partial()).isFalse();
                    assertThat(result.products()).containsExactlyInAnyOrder(detail("2"), detail("3"));
                })
                .verifyComplete();
    }

    @Test
    void returnsPartialResultWhenSomeDetailsFail() {
        when(similarProductIdsPort.findSimilarProductIds("1")).thenReturn(Mono.just(List.of("2", "3")));
        when(productDetailPort.findProductDetail("2")).thenReturn(Mono.just(detail("2")));
        when(productDetailPort.findProductDetail("3")).thenReturn(Mono.error(new RuntimeException("boom")));

        StepVerifier.create(useCase.getSimilarProducts("1"))
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

        StepVerifier.create(useCase.getSimilarProducts("1"))
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

        StepVerifier.create(useCase.getSimilarProducts("404"))
                .expectError(ProductNotFoundException.class)
                .verify();
    }

    @Test
    void returnsCompleteEmptyResultWhenNoSimilarIds() {
        when(similarProductIdsPort.findSimilarProductIds("1")).thenReturn(Mono.just(List.of()));

        StepVerifier.create(useCase.getSimilarProducts("1"))
                .assertNext(result -> {
                    assertThat(result.partial()).isFalse();
                    assertThat(result.products()).isEmpty();
                })
                .verifyComplete();
    }

    private static ProductDetail detail(String id) {
        return new ProductDetail(id, "name-" + id, BigDecimal.valueOf(9.99), true);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl domain/usecase -am test`
Expected: FAIL to compile — `SimilarProductsResult`/`GetSimilarProductsUseCase` don't exist yet.

- [ ] **Step 3: Write the implementation**

`domain/usecase/src/main/java/com/inditex/similarproducts/usecase/SimilarProductsResult.java`:

```java
package com.inditex.similarproducts.usecase;

import com.inditex.similarproducts.model.ProductDetail;
import java.util.List;

public record SimilarProductsResult(List<ProductDetail> products, boolean partial) {
}
```

`domain/usecase/src/main/java/com/inditex/similarproducts/usecase/GetSimilarProductsUseCase.java`:

```java
package com.inditex.similarproducts.usecase;

import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.model.port.ProductDetailPort;
import com.inditex.similarproducts.model.port.SimilarProductIdsPort;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
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

    public Mono<SimilarProductsResult> getSimilarProducts(String productId) {
        return similarProductIdsPort.findSimilarProductIds(productId)
                .flatMap(ids -> Flux.fromIterable(ids)
                        .flatMap(id -> fetchDetailOrEmpty(id), DETAIL_FETCH_CONCURRENCY)
                        .collectList()
                        .map(results -> toResult(ids.size(), results)));
    }

    private Mono<Optional<ProductDetail>> fetchDetailOrEmpty(String id) {
        return productDetailPort.findProductDetail(id)
                .map(Optional::of)
                .onErrorResume(error -> Mono.just(Optional.empty()));
    }

    private static SimilarProductsResult toResult(int requestedCount, List<Optional<ProductDetail>> results) {
        List<ProductDetail> products = results.stream()
                .flatMap(Optional::stream)
                .collect(Collectors.toList());
        boolean partial = products.size() < requestedCount;
        return new SimilarProductsResult(products, partial);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl domain/usecase -am test`
Expected: `BUILD SUCCESS`, 5 tests run, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add domain/usecase
git commit -m "$(cat <<'EOF'
Add GetSimilarProductsUseCase orchestration

Fans out to ProductDetailPort per similar id with bounded concurrency,
swallowing individual detail failures (never failing the batch) while
letting a base-product ids-lookup failure (e.g. 404) propagate
untouched.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Outbound Adapter — Similar Ids

**Files:**
- Create: `infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/MocksProperties.java`
- Create: `infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/WebClientConfig.java`
- Create: `infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/SimilarProductIdsAdapter.java`
- Test: `infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/WebClientConfigTest.java`
- Test: `infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/SimilarProductIdsAdapterTest.java`

**Interfaces:**
- Consumes: `SimilarProductIdsPort`, `ProductNotFoundException` (Task 2).
- Produces:
  - `com.inditex.similarproducts.infrastructure.out.rest.MocksProperties(String baseUrl, int maxConnections, MocksProperties.ProductDetailCache productDetailCache)` — `@ConfigurationProperties(prefix = "mocks")`, with nested `record ProductDetailCache(Duration ttl, long maxSize)`
  - `WebClientConfig#mocksWebClient(MocksProperties): WebClient` — `@Bean`
  - `SimilarProductIdsAdapter(WebClient, TimeLimiterRegistry, CircuitBreakerRegistry)` implementing `SimilarProductIdsPort`, package-private `@Component` (used again by Task 7's context)

`WebClientConfig` needs its own direct unit test: Task 4/5's adapter tests construct `WebClient.create(...)` themselves rather than going through this bean, so without `WebClientConfigTest` the class would show 0% coverage in this module's own JaCoCo bundle (the `application` module's context-load test exercises it too, but that coverage data lands in a different module's bundle and doesn't count here).

- [ ] **Step 1: Write the failing tests**

`infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/WebClientConfigTest.java`:

```java
package com.inditex.similarproducts.infrastructure.out.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebClientConfigTest {

    @Test
    void buildsWebClientWithConfiguredBaseUrl() {
        MocksProperties properties = new MocksProperties(
                "http://localhost:3001",
                500,
                new MocksProperties.ProductDetailCache(Duration.ofSeconds(30), 10_000));

        assertThat(new WebClientConfig().mocksWebClient(properties)).isNotNull();
    }
}
```

`infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/SimilarProductIdsAdapterTest.java`:

```java
package com.inditex.similarproducts.infrastructure.out.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.inditex.similarproducts.model.ProductNotFoundException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class SimilarProductIdsAdapterTest {

    private WireMockServer wireMockServer;
    private SimilarProductIdsAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();

        WebClient webClient = WebClient.create("http://localhost:" + wireMockServer.port());
        TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(500)).build());
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom().slidingWindowSize(20).build());

        adapter = new SimilarProductIdsAdapter(webClient, timeLimiterRegistry, circuitBreakerRegistry);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void returnsSimilarIdsOnSuccess() {
        wireMockServer.stubFor(get(urlEqualTo("/product/1/similarids"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[\"2\",\"3\"]")));

        StepVerifier.create(adapter.findSimilarProductIds("1"))
                .expectNext(List.of("2", "3"))
                .verifyComplete();
    }

    @Test
    void mapsNotFoundToProductNotFoundException() {
        wireMockServer.stubFor(get(urlEqualTo("/product/5/similarids"))
                .willReturn(aResponse().withStatus(404)));

        StepVerifier.create(adapter.findSimilarProductIds("5"))
                .expectError(ProductNotFoundException.class)
                .verify();
    }

    @Test
    void propagatesErrorWhenCallTimesOut() {
        wireMockServer.stubFor(get(urlEqualTo("/product/1/similarids"))
                .willReturn(aResponse()
                        .withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[\"2\"]")));

        StepVerifier.create(adapter.findSimilarProductIds("1"))
                .expectError()
                .verify();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl infrastructure/out/rest -am test`
Expected: FAIL to compile — `WebClientConfig`/`SimilarProductIdsAdapter` don't exist yet.

- [ ] **Step 3: Write the implementation**

`infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/MocksProperties.java`:

```java
package com.inditex.similarproducts.infrastructure.out.rest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mocks")
public record MocksProperties(String baseUrl, int maxConnections, ProductDetailCache productDetailCache) {

    public record ProductDetailCache(Duration ttl, long maxSize) {
    }
}
```

`infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/WebClientConfig.java`:

```java
package com.inditex.similarproducts.infrastructure.out.rest;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class WebClientConfig {

    @Bean
    WebClient mocksWebClient(MocksProperties properties) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("mocks-connection-pool")
                .maxConnections(properties.maxConnections())
                .build();
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000);
        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
```

`infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/SimilarProductIdsAdapter.java`:

```java
package com.inditex.similarproducts.infrastructure.out.rest;

import com.inditex.similarproducts.model.ProductNotFoundException;
import com.inditex.similarproducts.model.port.SimilarProductIdsPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
class SimilarProductIdsAdapter implements SimilarProductIdsPort {

    private static final String INSTANCE_NAME = "similarIdsService";
    private static final ParameterizedTypeReference<List<String>> ID_LIST_TYPE =
            new ParameterizedTypeReference<>() { };

    private final WebClient webClient;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;

    SimilarProductIdsAdapter(WebClient mocksWebClient, TimeLimiterRegistry timeLimiterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.webClient = mocksWebClient;
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
    }

    @Override
    public Mono<List<String>> findSimilarProductIds(String productId) {
        return webClient.get()
                .uri("/product/{productId}/similarids", productId)
                .retrieve()
                .bodyToMono(ID_LIST_TYPE)
                .onErrorMap(WebClientResponseException.NotFound.class,
                        notFound -> new ProductNotFoundException(productId))
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl infrastructure/out/rest -am test`
Expected: `BUILD SUCCESS`, 4 tests run (1 from `WebClientConfigTest` + 3 from `SimilarProductIdsAdapterTest`), 0 failures.

- [ ] **Step 5: Commit**

```bash
git add infrastructure/out/rest
git commit -m "$(cat <<'EOF'
Add outbound adapter for similar-ids lookup

WebClient-backed SimilarProductIdsAdapter wrapped with a named
Resilience4j TimeLimiter + CircuitBreaker, mapping a 404 to
ProductNotFoundException. Adds the shared WebClient bean and
MocksProperties binding for the module.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Outbound Adapter — Product Detail + Cache

**Files:**
- Create: `infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/ProductDetailAdapter.java`
- Test: `infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/ProductDetailAdapterTest.java`

**Interfaces:**
- Consumes: `ProductDetailPort`, `ProductDetail` (Task 2); `MocksProperties`, `WebClient` bean (Task 4).
- Produces: `ProductDetailAdapter(WebClient, TimeLimiterRegistry, CircuitBreakerRegistry, MocksProperties)` implementing `ProductDetailPort`, package-private `@Component`.

- [ ] **Step 1: Write the failing test**

`infrastructure/out/rest/src/test/java/com/inditex/similarproducts/infrastructure/out/rest/ProductDetailAdapterTest.java`:

```java
package com.inditex.similarproducts.infrastructure.out.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.inditex.similarproducts.model.ProductDetail;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class ProductDetailAdapterTest {

    private WireMockServer wireMockServer;
    private ProductDetailAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();

        WebClient webClient = WebClient.create("http://localhost:" + wireMockServer.port());
        TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(500)).build());
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom().slidingWindowSize(20).build());
        MocksProperties properties = new MocksProperties(
                "http://localhost:" + wireMockServer.port(),
                500,
                new MocksProperties.ProductDetailCache(Duration.ofSeconds(30), 10_000));

        adapter = new ProductDetailAdapter(webClient, timeLimiterRegistry, circuitBreakerRegistry, properties);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void returnsProductDetailOnSuccess() {
        wireMockServer.stubFor(get(urlEqualTo("/product/1"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}")));

        StepVerifier.create(adapter.findProductDetail("1"))
                .expectNext(new ProductDetail("1", "Shirt", BigDecimal.valueOf(9.99), true))
                .verifyComplete();
    }

    @Test
    void propagatesErrorOnNotFound() {
        wireMockServer.stubFor(get(urlEqualTo("/product/5"))
                .willReturn(aResponse().withStatus(404)));

        StepVerifier.create(adapter.findProductDetail("5"))
                .expectError()
                .verify();
    }

    @Test
    void secondCallForSameIdIsServedFromCache() {
        wireMockServer.stubFor(get(urlEqualTo("/product/1"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}")));

        adapter.findProductDetail("1").block();
        adapter.findProductDetail("1").block();

        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/product/1")));
    }

    @Test
    void failedCallIsNotCached() {
        wireMockServer.stubFor(get(urlEqualTo("/product/6"))
                .willReturn(aResponse().withStatus(500)));

        StepVerifier.create(adapter.findProductDetail("6")).expectError().verify();
        StepVerifier.create(adapter.findProductDetail("6")).expectError().verify();

        wireMockServer.verify(2, getRequestedFor(urlEqualTo("/product/6")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl infrastructure/out/rest -am test`
Expected: FAIL to compile — `ProductDetailAdapter` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

`infrastructure/out/rest/src/main/java/com/inditex/similarproducts/infrastructure/out/rest/ProductDetailAdapter.java`:

```java
package com.inditex.similarproducts.infrastructure.out.rest;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.model.port.ProductDetailPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
class ProductDetailAdapter implements ProductDetailPort {

    private static final String INSTANCE_NAME = "productDetailService";

    private final WebClient webClient;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    private final AsyncCache<String, ProductDetail> cache;

    ProductDetailAdapter(WebClient mocksWebClient, TimeLimiterRegistry timeLimiterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry, MocksProperties properties) {
        this.webClient = mocksWebClient;
        this.timeLimiter = timeLimiterRegistry.timeLimiter(INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.productDetailCache().ttl())
                .maximumSize(properties.productDetailCache().maxSize())
                .buildAsync();
    }

    @Override
    public Mono<ProductDetail> findProductDetail(String productId) {
        return Mono.fromFuture(() -> cache.get(productId, (id, executor) -> fetch(id).toFuture()));
    }

    private Mono<ProductDetail> fetch(String productId) {
        return webClient.get()
                .uri("/product/{productId}", productId)
                .retrieve()
                .bodyToMono(ProductDetail.class)
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
```

Caffeine's `AsyncCache` automatically evicts an entry whose backing `CompletableFuture` completes exceptionally, which is what implements "only cache successes" here — no extra code needed.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl infrastructure/out/rest -am test`
Expected: `BUILD SUCCESS`, 8 tests run total (4 from Task 4 + 4 here), 0 failures.

- [ ] **Step 5: Commit**

```bash
git add infrastructure/out/rest
git commit -m "$(cat <<'EOF'
Add outbound adapter for product-detail lookup with success-only cache

ProductDetailAdapter wraps its resilience-guarded WebClient call with
a Caffeine AsyncCache keyed by productId; failed lookups are never
cached, relying on Caffeine's built-in eviction of exceptionally
completed futures.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Inbound Adapter

**Files:**
- Create: `infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsController.java`
- Create: `infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/GlobalExceptionHandler.java`
- Test: `infrastructure/in/rest/src/test/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsControllerTest.java`

**Interfaces:**
- Consumes: `GetSimilarProductsUseCase`, `SimilarProductsResult` (Task 3); `ProductDetail`, `ProductNotFoundException` (Task 2).
- Produces: `SimilarProductsController` (`GET /product/{productId}/similar`), `GlobalExceptionHandler` (`@RestControllerAdvice`).

- [ ] **Step 1: Write the failing test**

`infrastructure/in/rest/src/test/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsControllerTest.java`:

```java
package com.inditex.similarproducts.infrastructure.in.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.model.ProductNotFoundException;
import com.inditex.similarproducts.usecase.GetSimilarProductsUseCase;
import com.inditex.similarproducts.usecase.SimilarProductsResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        when(useCase.getSimilarProducts("1"))
                .thenReturn(Mono.just(new SimilarProductsResult(List.of(detail), false)));

        webTestClient.get().uri("/product/1/similar")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductDetail.class).contains(detail);
    }

    @Test
    void returnsPartialContentWhenResultIsPartial() {
        when(useCase.getSimilarProducts("4"))
                .thenReturn(Mono.just(new SimilarProductsResult(List.of(), true)));

        webTestClient.get().uri("/product/4/similar")
                .exchange()
                .expectStatus().isEqualTo(206)
                .expectBody().jsonPath("$").isArray();
    }

    @Test
    void returnsNotFoundWhenBaseProductMissing() {
        when(useCase.getSimilarProducts("404")).thenReturn(Mono.error(new ProductNotFoundException("404")));

        webTestClient.get().uri("/product/404/similar")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void returnsServerErrorOnUnexpectedFailure() {
        when(useCase.getSimilarProducts("1")).thenReturn(Mono.error(new RuntimeException("boom")));

        webTestClient.get().uri("/product/1/similar")
                .exchange()
                .expectStatus().is5xxServerError();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl infrastructure/in/rest -am test`
Expected: FAIL to compile — `SimilarProductsController`/`GlobalExceptionHandler` don't exist yet.

- [ ] **Step 3: Write the implementation**

`infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/SimilarProductsController.java`:

```java
package com.inditex.similarproducts.infrastructure.in.rest;

import com.inditex.similarproducts.model.ProductDetail;
import com.inditex.similarproducts.usecase.GetSimilarProductsUseCase;
import com.inditex.similarproducts.usecase.SimilarProductsResult;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
class SimilarProductsController {

    private final GetSimilarProductsUseCase useCase;

    SimilarProductsController(GetSimilarProductsUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/product/{productId}/similar")
    Mono<ResponseEntity<List<ProductDetail>>> getSimilar(@PathVariable String productId) {
        return useCase.getSimilarProducts(productId)
                .map(SimilarProductsController::toResponse);
    }

    private static ResponseEntity<List<ProductDetail>> toResponse(SimilarProductsResult result) {
        HttpStatus status = result.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.products());
    }
}
```

`infrastructure/in/rest/src/main/java/com/inditex/similarproducts/infrastructure/in/rest/GlobalExceptionHandler.java`:

```java
package com.inditex.similarproducts.infrastructure.in.rest;

import com.inditex.similarproducts.model.ProductNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<Void> handleNotFound(ProductNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Void> handleUnexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl infrastructure/in/rest -am test`
Expected: `BUILD SUCCESS`, 4 tests run, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add infrastructure/in/rest
git commit -m "$(cat <<'EOF'
Add inbound REST adapter for GET /product/{productId}/similar

Maps SimilarProductsResult to 200 (complete) or 206 (partial), and
ProductNotFoundException to 404 via a RestControllerAdvice. 206 is a
deliberate extension beyond similarProducts.yaml, agreed in the design
spec.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Application Bootstrap

**Files:**
- Create: `application/src/main/java/com/inditex/similarproducts/Application.java`
- Create: `application/src/main/java/com/inditex/similarproducts/UseCaseConfig.java`
- Create: `application/src/main/resources/application.yml`
- Test: `application/src/test/java/com/inditex/similarproducts/ApplicationContextLoadsTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2–6 (component-scanned transitively since all packages sit under `com.inditex.similarproducts`).
- Produces: a bootable Spring Boot application (`Application`), and `UseCaseConfig#getSimilarProductsUseCase(SimilarProductIdsPort, ProductDetailPort): GetSimilarProductsUseCase` — the one place `domain/usecase` is wired into Spring, kept out of `domain/usecase` itself per rule 1.

- [ ] **Step 1: Write `application.yml`**

`application/src/main/resources/application.yml`:

```yaml
server:
  port: 5000

mocks:
  base-url: http://localhost:3001
  max-connections: 500
  product-detail-cache:
    ttl: 30s
    max-size: 10000

resilience4j:
  timelimiter:
    instances:
      similarIdsService:
        timeout-duration: 2s
      productDetailService:
        timeout-duration: 2s
  circuitbreaker:
    instances:
      similarIdsService:
        sliding-window-size: 20
        sliding-window-type: COUNT_BASED
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 5s
        ignore-exceptions:
          - com.inditex.similarproducts.model.ProductNotFoundException
      productDetailService:
        sliding-window-size: 20
        sliding-window-type: COUNT_BASED
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 5s
```

- [ ] **Step 2: Write `Application` and `UseCaseConfig`**

`application/src/main/java/com/inditex/similarproducts/Application.java`:

```java
package com.inditex.similarproducts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

`application/src/main/java/com/inditex/similarproducts/UseCaseConfig.java`:

```java
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
```

- [ ] **Step 3: Write the failing context-load test**

`application/src/test/java/com/inditex/similarproducts/ApplicationContextLoadsTest.java`:

```java
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
```

- [ ] **Step 4: Run test to verify it fails, then passes**

Run: `mvn -q -pl application -am test`
Expected first run: FAIL — module/class not yet wired (if run before Step 2's files exist). After Steps 1–3 are all in place: `BUILD SUCCESS`, 1 test run, 0 failures. The full Spring context loads without hitting the network — `WebClient`/Resilience4j registries are configured lazily.

- [ ] **Step 5: Commit**

```bash
git add application
git commit -m "$(cat <<'EOF'
Add Spring Boot bootstrap and wiring

Application boots on port 5000; UseCaseConfig is the single place
GetSimilarProductsUseCase is constructed from its two port beans,
keeping domain/usecase free of Spring dependencies. application.yml
carries all mocks/resilience/cache tunables.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: End-to-End Acceptance and Final Gate

**Files:** none (verification only).

**Interfaces:**
- Consumes: the complete system from Tasks 1–7.
- Produces: a verified, running `yourApp` passing both the build-enforced quality gate and the real k6 load test — the deliverable the whole plan exists to produce.

This task follows `superpowers:verification-before-completion`: every claim below is backed by a command actually run, not assumed.

- [ ] **Step 1: Run the full build gate**

Run: `mvn -q -f pom.xml clean verify`
Expected: `BUILD SUCCESS` across all modules — tests pass, JaCoCo reports ≥95% line coverage per module (Application.class excluded), Checkstyle reports zero error-severity violations. If coverage or Checkstyle fails, fix the flagged module before continuing — do not lower the threshold (per spec §7).

- [ ] **Step 2: Start the mock infrastructure**

```bash
docker-compose up -d simulado influxdb grafana
curl -sf http://localhost:3001/product/1/similarids
```

Expected: the curl returns `["2","3","4"]` (from `shared/simulado/mocks.json`), confirming the mocks are up.

- [ ] **Step 3: Start the application**

```bash
java -jar application/target/application.jar
```

Expected: log shows `Started Application` and `Netty started on port 5000`.

- [ ] **Step 4: Manually probe each contract case**

```bash
curl -i http://localhost:5000/product/1/similar   # expect 200, 3 products
curl -i http://localhost:5000/product/4/similar   # expect 206 — id 5 in its similar list 404s
curl -i http://localhost:5000/product/5/similar   # expect 206 — id 6 in its similar list 500s
curl -i http://localhost:5000/product/999/similar # expect 404 — base product's similarids 404s
```

Expected: status codes match the comments above; the 206 responses' bodies contain the successfully-resolved products only.

- [ ] **Step 5: Run the k6 load test**

```bash
docker-compose run --rm k6 run scripts/test.js
```

Expected: the k6 summary completes without the process crashing; open `http://localhost:3000/d/Le2Ku9NMk/k6-performance-test` in a browser and confirm request latency stays bounded (well under the mocks' 5s/50s worst-case delays, thanks to the 2s TimeLimiter and circuit breaker) and the app process is still responsive to `curl http://localhost:5000/product/1/similar` after the run.

- [ ] **Step 6: Tear down**

```bash
docker-compose down
```

- [ ] **Step 7: Record any fixes as a follow-up commit**

If Steps 1–5 required any code changes to pass, stage and commit them now with a message describing what the acceptance run caught. If nothing needed fixing, no commit is needed for this task.
