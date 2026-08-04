# Slotwise — career-course-project

Booking/reservations platform used as a course project across a backend learning
roadmap (Spring Boot advanced → PostgreSQL internals → Redis → Kafka → Docker → K8s).

## Structure

- Multi-module Maven build: root `pom.xml` (packaging `pom`) imports
  `spring-boot-dependencies:4.0.1` as a BOM in `dependencyManagement` — it does
  **not** extend `spring-boot-starter-parent`. Only module so far: `booking-service`.
- `booking-service`: Spring Boot 4 app, Java 25, Postgres via Spring Data JPA.
  - `data/` — JPA entities (`Resource`, `Reservation`, `ReservationStatus`) + repositories
  - `model/` — request/response records/DTOs (`CreateResourceRequest`, `ResourceDto`, etc.)
  - `service/` — `ResourceService`, `ReservationService` (constructor injection, `@Transactional`),
    converters, domain exceptions (`ResourceNotFoundException`, `ReservationConflictException`)
  - `controller/` — REST controllers + `GlobalExceptionHandler` (`@ControllerAdvice`)
  - `security/` — `SecurityConfig` (OAuth2 Resource Server + `@EnableMethodSecurity`,
    JWT → `ROLE_*` authority mapping off Keycloak's `realm_access.roles` claim)
- `docker-compose.yml` — local Postgres 17 (`slotwise`/`slotwise`/`slotwise`) and
  Keycloak 26 (OIDC Authorization Server, port `8081`, realm auto-imported from
  `keycloak/realm-export.json` via `--import-realm`).
- `.sdkmanrc` — Java 25.0.4-tem, Maven 3.9.11.

## Known gotchas (already hit once, don't re-debug from scratch)

- **Lombok annotation processing silently no-ops** because the parent pom only imports
  the Spring Boot BOM instead of extending `spring-boot-starter-parent` — so the
  `annotationProcessorPaths` wiring that Spring Initializr normally generates is missing.
  Fixed in `booking-service/pom.xml` by adding an explicit `maven-compiler-plugin`
  config with `annotationProcessorPaths` → lombok. If a new module is added and gets the
  same "cannot find symbol: getX/setX/builder()" errors, apply the same fix there (or
  move the compiler-plugin config up to the parent's `pluginManagement`).
- **Testcontainers 2.x renamed its module artifact IDs.** `org.testcontainers:junit-jupiter`
  and `org.testcontainers:postgresql` (the 1.x names) don't exist in `testcontainers-bom`
  2.x — they're `testcontainers-junit-jupiter` / `testcontainers-postgresql` now. Fixed in
  `booking-service/pom.xml` by switching to the renamed artifact IDs and dropping the
  redundant `testcontainers.version` property/explicit versions (Spring Boot 4.0.1's own
  BOM already manages `testcontainers-bom` transitively). **Verified end-to-end**: with
  Docker Desktop's WSL integration on, `mvn -pl booking-service test` runs
  `ReservationServiceIntegrationTest` against a real Testcontainers Postgres — 2/2 pass.
- **`ConversionService` is only auto-registered in a web context.** `ResourceService`/
  `ReservationService` inject `ConversionService` to run the `@Component Converter<...>`
  beans; that bean only exists in production because `WebMvcAutoConfiguration` creates
  one (`mvcConversionService`) when the app boots as a servlet web app. A
  `@SpringBootTest` with `webEnvironment = NONE` skips that autoconfiguration, so no
  `ConversionService` bean exists and context loading fails with
  `NoSuchBeanDefinitionException`. Fixed by adding a nested `@TestConfiguration` in
  `ReservationServiceIntegrationTest` that builds a `DefaultConversionService` and
  registers all `Converter<?, ?>` beans into it (same pattern used in the
  `app-mvn-alpha-operating-costs-api` project's service tests) — keeps the test on
  `WebEnvironment.NONE` instead of paying for a full servlet context just to get a
  `ConversionService`. Any future non-web test that needs `ResourceService`/
  `ReservationService` will hit the same gap and needs the same `@TestConfiguration`.
- **Spring's `@NonNullApi`/`@NonNullFields` are deprecated under Spring 7** (pulled in by
  Spring Boot 4.0.1). Every `package-info.java` uses JSpecify's `@NullMarked` instead
  (`org.jspecify.annotations.NullMarked` — ships transitively via `spring-core`, no new
  dependency needed).
- **`@PathVariable` without an explicit name needs the compiler's `-parameters`
  flag.** `spring-boot-starter-parent` sets it by default; this project doesn't
  extend it, so unnamed `@PathVariable Long id`-style params failed at runtime with
  "parameter name information not available via reflection". Fixed by adding
  `<parameters>true</parameters>` to `booking-service/pom.xml`'s
  `maven-compiler-plugin` config — and note `mvn clean compile` (not just
  `compile`) is needed once to pick up a compiler-flag-only change.
- **Keycloak realm import needs `firstName`/`lastName` on every user** or the password
  grant fails with `invalid_grant: Account is not fully set up` (Keycloak 26's User
  Profile feature implicitly requires them; the gap doesn't show in the user's
  `requiredActions` list). Also, re-importing after editing `keycloak/realm-export.json`
  needs `docker compose up -d --force-recreate keycloak` — `--import-realm` skips a
  realm that already exists by name, and there's no named volume for Keycloak's own
  storage so recreating the container clears it.
- Session/terminal history for this project has been lost before mid-session. This file
  plus git commits (once initialized) are the durable record — prefer committing working
  states over relying on conversation recovery.

## Course roadmap (recovered from prior session — the "why" behind this project)

This project is the hands-on companion to a backend-senior roadmap. Phases as recovered:

- **Fase 1 (Meses 1-3) — Spring Boot Avanzado**: Spring Security (JWT, OAuth2, OIDC,
  Resource Server, Method Security, `@PreAuthorize` and when *not* to use it); Testing
  (JUnit 5, Mockito, Testcontainers, Integration Tests — target 80% integration / 20%
  unit); Configuration (`@ConfigurationProperties`, Profiles, Externalized Config,
  Secrets); Validation (Bean Validation, Custom Validators); global `@ControllerAdvice`
  error handling; Logs (SLF4J, Logback, MDC, Correlation IDs). Project: full enterprise API.
- **Fase 2 (Meses 3-5) — PostgreSQL Avanzado**: MVCC internals; isolation levels (Read
  Committed, Repeatable Read, Serializable); Locks, `FOR UPDATE`, deadlocks; Indexes
  (BTree, GIN, GiST, Partial, Composite); `EXPLAIN ANALYZE` until natural; Partitioning
  (Range, List, Hash); Replication (Read Replicas, Primary-Replica). Project: optimize
  real queries against millions of generated rows, measure.
- **Fase 3 (Meses 5-6) — Redis**: patterns, not the API. Cache-Aside, Write-Through,
  Write-Behind; Distributed Locks (Redisson); Rate Limiting (Token Bucket, Sliding
  Window); Pub/Sub and its limitations. Project: add user cache, rate limiting, sessions.
- **Fase 4 (Meses 6-8) — Kafka**: Topics, Partitions, Offsets, Consumer Groups;
  delivery guarantees (At Most Once, At Least Once, Exactly Once); patterns (Event
  Driven, Outbox, Saga, CQRS, Event Sourcing conceptually). Project: split into
  User Service / Notification Service / Audit Service communicating via Kafka.
- **Fase 5 (Meses 8-9) — Docker**: Dockerfile, Layers, Multi-stage builds, Volumes,
  Networks, Healthchecks; Compose to bring up Postgres + Redis + Kafka + Spring with
  one command.
- **Fase 6 (Meses 9-12) — Kubernetes**: Pods, Deployments, Services, ConfigMaps,
  Secrets, Ingress; scaling (HPA, Requests/Limits), Networking — "nivel senior serio."
- **Resilience & architecture topics** (called out explicitly by the user, not yet
  slotted into a numbered phase in what was recovered — likely later Fase 1/enterprise
  hardening or a dedicated architecture phase): **Retry**, **Bulkhead** (resilience
  patterns, e.g. via Resilience4j), **Idempotencia** (idempotency keys/handling for
  APIs and consumers), and design/architecture: **DDD** (Domain-Driven Design),
  **Hexagonal Architecture** (ports & adapters), **Clean Architecture**.

The rest of the roadmap past Kubernetes was not recovered — if the user references
"Fase 7" or later content, ask them to re-paste it rather than assuming what's there.

## Conventions

- Follow the user's global Java/Spring `static`/domain-modeling guidelines in
  `~/.claude/CLAUDE.md` for all code in this repo.
- Follow the backend best-practices notes in `~/Downloads/code/back/` (dto-patterns.md,
  jpa-best-practices.md, spring-boot-architecture.md, java-best-practices.md):
  - **Reads skip the entity + `ConversionService`.** Query straight into the `record`
    DTO with a JPQL constructor expression (`SELECT new ...Dto(...)`) — see
    `ResourceRepository.findSummaryById`/`findAllSummaries` and
    `ReservationRepository.findSummariesByResourceId`. Keep entity + `ConversionService`
    only for create/update/delete, where the entity is actually mutated/persisted.
  - Explicit `this.` on every instance field/method access from within the class
    (`this.resourceRepository.save(...)`, not `resourceRepository.save(...)`).
  - Local variables declared `final` (`final var x = ...` / `final Type x = ...`).
  - Constructor injection via `@RequiredArgsConstructor` (Lombok) over
    `@AllArgsConstructor` or manual constructors/field `@Autowired` — already the
    convention in every `@Service`/`@RestController` here.
  - `@Validated` on every `@Service` class, with `@Valid`/`@NotNull` on its public
    method parameters (`ResourceService`, `ReservationService`). Without `@Validated`,
    Bean Validation annotations on a service method's parameters are silently ignored —
    validation only happened to work before because the controller's `@Valid
    @RequestBody` covers that path, but calling the service directly (another service,
    a test) bypassed it entirely. `@Validated` on a service throws
    `ConstraintViolationException` (not `MethodArgumentNotValidException`, which is
    controller-only) — `GlobalExceptionHandler` handles both.
  - **`@Valid` alone does not reject a `null` argument.** `@Valid` only cascades
    validation into the object's own fields; if the parameter itself is `null` there's
    nothing to cascade into, so no violation is raised. Every request-body parameter
    validated with `@Valid` also needs `@NotNull` alongside it
    (`@NotNull @Valid CreateResourceRequest request`) to catch a null argument at the
    service boundary.
- `ddl-auto: update` is a deliberate course-project shortcut (see comment in
  `application.yml`) — replace with Flyway migrations once Phase 2 (indexes/partitions)
  of the roadmap starts.
