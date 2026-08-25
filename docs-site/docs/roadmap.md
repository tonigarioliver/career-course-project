---
icon: lucide/map
---

# Course roadmap

Backend-senior roadmap this project is the hands-on companion to. Phases as recovered
from the original course material.

## Fase 1 (Meses 1-3) — Spring Boot Avanzado

- [x] **Testing** — JUnit 5, Testcontainers (Postgres), Integration Tests. Verified
      end-to-end: `mvn -pl booking-service test` runs `ReservationServiceIntegrationTest`
      against a real Testcontainers Postgres, 2/2 passing.
- [x] **Validation** — Bean Validation on DTOs (`@NotBlank`/`@NotNull`), `@Validated` +
      `@NotNull @Valid` on service method parameters, `@ControllerAdvice`
      (`GlobalExceptionHandler`) handling both `MethodArgumentNotValidException` and
      `ConstraintViolationException`.
- [x] **Security** — Keycloak (OIDC Authorization Server) added to `docker-compose.yml`
      with an imported realm (`keycloak/realm-export.json`: roles, a public client,
      two test users). `booking-service` is an OAuth2 **Resource Server**
      (`spring-boot-starter-oauth2-resource-server`) validating Keycloak-issued JWTs
      via `issuer-uri`. `@EnableMethodSecurity` + `@PreAuthorize` restrict resource
      management to the `RESERVATION_ADMIN` role. Verified end-to-end: no token →
      `401`, wrong role → `403`, right role → `201`.
- [x] **Configuration** — `ReservationProperties` (`@ConfigurationProperties`, prefix
      `slotwise.reservation`) registered via a dedicated `@Configuration` class
      (`ReservationConfig`) instead of `@ConfigurationPropertiesScan`, wired into
      `ReservationService` to enforce min/max reservation duration. `application-prod.yml`
      profile drops every dev-friendly default (DB creds, Keycloak issuer) so a prod
      deploy missing an env var fails to start instead of silently using localhost
      values, and switches `ddl-auto` to `validate`. Externalized config/secrets
      already covered by the `${VAR:default}` placeholders in `application.yml`.
- [x] **Logs** — `CorrelationIdFilter` (hand-rolled `OncePerRequestFilter`) reads/mints an
      `X-Correlation-Id` per request, puts it in SLF4J's MDC for the request's thread,
      echoes it back on the response, clears it in a `finally`. `logging.pattern.console`
      includes `%X{correlationId}` so every log line from that request is grep-able
      together. `ReservationService` logs on create/conflict via Lombok's `@Slf4j`. See
      "MDC is thread-local — deliberately hand-rolled, not Micrometer Tracing (yet)" in
      decisions.md for why this wasn't done with Spring's built-in tracing library, and
      why Fase 4 (Kafka) is where that changes.

Project target: a full enterprise API.

## Fase 2 (Meses 3-5) — PostgreSQL Avanzado

- [x] **MVCC internals / Isolation levels / Locks / `FOR UPDATE`** — reproduced the
      classic check-then-insert race in `ReservationService.create()` with two real
      concurrent transactions under Postgres' default Read Committed: both transactions'
      `SELECT` saw "no overlap" because neither had committed yet, both inserted, both
      committed — two overlapping reservations. Fixed with two independent layers:
      `SELECT ... FOR UPDATE` on the `Resource` row (`ResourceRepository.findByIdForUpdate`,
      `@Lock(PESSIMISTIC_WRITE)`) serializes concurrent `create()` calls for the same
      resource so the loser sees the winner's committed row and conflicts cleanly; a
      `reservations_no_overlap` EXCLUDE constraint (GiST, see Indexes below) as the DB-level
      backstop if the app-level lock is ever bypassed. Verified with a real two-thread
      `ExecutorService` integration test — no artificial `sleep` needed, the row lock
      forces the interleaving deterministically. Full before/after in decisions.md.
      Also confirmed *why not a plain Java lock*: `synchronized`/`ReentrantLock` only
      hold within one JVM — useless once `booking-service` runs as more than one
      instance (Fase 6).
- [x] **Deadlocks and `SERIALIZABLE`** — reproduced a real deadlock (two sessions locking
      two rows in opposite order; Postgres' detector found the cycle and aborted one with
      `deadlock detected`) and a write-skew anomaly (two transactions touching *different*
      rows, no lock ever contended, that together silently violate an invariant under
      Read Committed but get one aborted with `40001 serialization_failure` under
      `SERIALIZABLE`). Both against the real primary, no app code involved — pure Postgres
      mechanics. Full detail (and why `SERIALIZABLE` wasn't retrofitted onto
      `ReservationService`, which already has a single lockable row) in decisions.md.
- [x] **Indexes (Composite BTree, GiST, Partial)**
    - Composite BTree: `idx_reservations_resource_time` on `(resource_id, start_time,
      end_time)`, backing `ReservationRepository.findOverlapping`. Measured against 3M
      seeded rows (`scripts/seed-fase2-data.sql`): **132ms parallel seq scan → 0.12ms
      bitmap index scan** (~1000x), ~34,000 buffers → 11. Made permanent via
      `@Table(indexes = @Index(...))` on `Reservation`.
    - GiST + Partial: `reservations_no_overlap` EXCLUDE constraint (see Locks above) is
      itself backed by a GiST index, restricted to non-cancelled rows via `WHERE` (a
      partial constraint, same mechanism as a partial index). Needed `btree_gist` to mix
      a plain equality column (`resource_id`) with a range-overlap column in one index.
      Full `EXPLAIN ANALYZE`/constraint-creation detail in decisions.md. GIN still to cover.
- [x] `EXPLAIN ANALYZE` until natural — beyond `findOverlapping` (above), caught a real
      bug in `findSummariesByResourceId`'s pagination: no `ORDER BY` let the planner's
      uniform-distribution assumption for `LIMIT` pick a `Seq Scan` for some `resource_id`
      values (data is physically clustered by resource, not uniform) — 9.6ms/1548 buffers
      vs 0.12ms/16 for the same query once `ORDER BY r.startTime, r.id` let it reliably
      use the index instead. Also fixed a real non-determinism bug (pagination without
      an explicit order). Full detail in decisions.md.
- [x] **Partitioning** — `reservations` is `PARTITION BY HASH (resource_id)`, 8 buckets
      (`V1__partition_reservations_by_resource_hash.sql`). Range by `start_time` was the
      first instinct (time-series-shaped data) but rejected: `reservations_no_overlap`
      only enforces within a single partition, and two reservations either side of a
      month boundary could still overlap in real time while landing in different date
      partitions — silently defeating the constraint at exactly the boundary it exists
      to guard. Hash by `resource_id` keeps every reservation for one resource in the
      same partition regardless of date, so the per-resource invariant stays fully
      contained, and `findOverlapping`'s `WHERE resource_id = ?` already prunes to one
      partition. Traded away range-by-date's "drop old month cheaply" archival story.
      Schema ownership moved from `ddl-auto=update` to **Flyway** in the same change —
      partitioning has no JPA annotation, same reason the old EXCLUDE-constraint
      `ApplicationRunner` existed (now folded into the migration). Full rationale in
      decisions.md.
- [x] **Replication (Read Replicas, Primary-Replica)** — real Postgres streaming
      replication in `docker-compose.yml` (`postgres-replica`, a `pg_basebackup`-cloned hot
      standby streaming the primary's WAL), plus the app routing every
      `@Transactional(readOnly = true)` call to it via a `AbstractRoutingDataSource` +
      `LazyConnectionDataSourceProxy` (`DataSourceConfig`). Verified end-to-end against the
      real containers: replica rejects writes, a primary write appears on it within ~1s,
      and a real authenticated request measurably lands on the replica's connection pool.
      Caught and fixed a real bug along the way — `@ServiceConnection` in the integration
      test doesn't rewrite `spring.datasource.*`, so the first cut of the wiring silently
      wrote test data into the real dev database instead of the ephemeral Testcontainers
      one. Full detail in decisions.md.

Project target: optimize real queries against millions of generated rows, measure.

## Fase 3 (Meses 5-6) — Redis

- [x] Cache-Aside, Write-Through (Write-Behind deferred, see decisions.md).
- [x] Distributed Locks (Redisson).
- [x] Rate Limiting (Token Bucket via Redisson `RRateLimiter`; Sliding Window skipped, see decisions.md).
- [x] Pub/Sub and its limitations.

Project target: add user cache, rate limiting, sessions.

## Fase 4 (Meses 6-8) — Kafka

- [ ] Topics, Partitions, Offsets, Consumer Groups.
- [ ] Delivery guarantees (At Most Once, At Least Once, Exactly Once).
- [ ] Patterns: Event Driven, Outbox, Saga, CQRS, Event Sourcing (conceptually).
- [ ] **Migrate correlation IDs to Micrometer Tracing** (`micrometer-tracing-bridge-brave`
      or `-otel`) once there's more than one service. The hand-rolled `CorrelationIdFilter`
      from Fase 1 only correlates logs *within* `booking-service` — it has no way to carry
      an ID across a Kafka message to `notification-service`/`audit-service` without
      manually stuffing it into every message's headers by hand. Micrometer Tracing
      auto-generates `traceId`/`spanId`, has built-in Kafka producer/consumer
      instrumentation that propagates the trace through message headers for free, and can
      export to Zipkin/Tempo to see one request's whole path across all three services
      visually. See decisions.md for the full rationale.

Project target: split into User Service / Notification Service / Audit Service
communicating via Kafka.

## Fase 5 (Meses 8-9) — Docker

- [ ] Dockerfile, Layers, Multi-stage builds, Volumes, Networks, Healthchecks.
- [ ] Compose to bring up Postgres + Redis + Kafka + Spring with one command.

## Fase 6 (Meses 9-12) — Kubernetes

- [ ] Pods, Deployments, Services, ConfigMaps, Secrets, Ingress.
- [ ] Scaling (HPA, Requests/Limits), Networking — "nivel senior serio."

## Resilience & architecture (not yet slotted into a numbered phase)

- [ ] **Retry**, **Bulkhead** (Resilience4j).
- [ ] **Idempotencia** (idempotency keys/handling for APIs and consumers).
- [ ] **DDD** (Domain-Driven Design), **Hexagonal Architecture**, **Clean Architecture**.

!!! warning "Past Fase 6"

    The roadmap past Kubernetes hasn't been recovered. If it references "Fase 7" or
    later content, that needs to be re-supplied rather than assumed.
