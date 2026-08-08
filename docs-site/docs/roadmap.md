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
- [ ] **Logs** — SLF4J, Logback, MDC, Correlation IDs.

Project target: a full enterprise API.

## Fase 2 (Meses 3-5) — PostgreSQL Avanzado

- [ ] MVCC internals.
- [ ] Isolation levels (Read Committed, Repeatable Read, Serializable).
- [ ] Locks, `FOR UPDATE`, deadlocks.
- [ ] Indexes (BTree, GIN, GiST, Partial, Composite).
- [ ] `EXPLAIN ANALYZE` until natural.
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
