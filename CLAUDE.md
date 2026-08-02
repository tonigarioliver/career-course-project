# Slotwise — career-course-project

Booking/reservations platform used as a course project across a backend learning
roadmap (Spring Boot advanced → PostgreSQL internals → Redis → Kafka → Docker → K8s).
Not a git repo yet.

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
- `docker-compose.yml` — local Postgres 17 (`slotwise`/`slotwise`/`slotwise`).
- `.sdkmanrc` — Java 25.0.4-tem, Maven 3.9.11.

## Known gotchas (already hit once, don't re-debug from scratch)

- **Lombok annotation processing silently no-ops** because the parent pom only imports
  the Spring Boot BOM instead of extending `spring-boot-starter-parent` — so the
  `annotationProcessorPaths` wiring that Spring Initializr normally generates is missing.
  Fixed in `booking-service/pom.xml` by adding an explicit `maven-compiler-plugin`
  config with `annotationProcessorPaths` → lombok. If a new module is added and gets the
  same "cannot find symbol: getX/setX/builder()" errors, apply the same fix there (or
  move the compiler-plugin config up to the parent's `pluginManagement`).
- **`testcontainers.version` in root `pom.xml` is pinned to `2.0.5`**, which does not
  exist on Maven Central (real Testcontainers releases are on the `1.20.x` line as of
  this writing). This breaks test-scope dependency resolution
  (`org.testcontainers:junit-jupiter:jar:2.0.5`, `org.testcontainers:postgresql:jar:2.0.5`
  not found). Needs correcting to a real released version — in progress.
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
- `ddl-auto: update` is a deliberate course-project shortcut (see comment in
  `application.yml`) — replace with Flyway migrations once Phase 2 (indexes/partitions)
  of the roadmap starts.
