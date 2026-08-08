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

- [ ] MVCC internals.
- [ ] Isolation levels (Read Committed, Repeatable Read, Serializable).
- [ ] Locks, `FOR UPDATE`, deadlocks.
- [x] **Indexes (Composite)** — `idx_reservations_resource_time` on
      `(resource_id, start_time, end_time)`, backing `ReservationRepository.findOverlapping`.
      Measured against 3M seeded rows (`scripts/seed-fase2-data.sql`): **132ms parallel seq
      scan → 0.12ms bitmap index scan** (~1000x), ~34,000 buffers → 11. Made permanent via
      `@Table(indexes = @Index(...))` on `Reservation`. Full `EXPLAIN ANALYZE` before/after
      in decisions.md. BTree/GIN/GiST/Partial still to cover — this was one composite BTree.
- [ ] `EXPLAIN ANALYZE` until natural — started (see above), keep reading plans as more
      queries/indexes get added.
- [ ] Partitioning (Range, List, Hash).
- [ ] Replication (Read Replicas, Primary-Replica).

Project target: optimize real queries against millions of generated rows, measure.

## Fase 3 (Meses 5-6) — Redis

- [ ] Cache-Aside, Write-Through, Write-Behind.
- [ ] Distributed Locks (Redisson).
- [ ] Rate Limiting (Token Bucket, Sliding Window).
- [ ] Pub/Sub and its limitations.

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
