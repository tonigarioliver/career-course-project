# Audit Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split a new `audit-service` out of the monolith: it consumes `reservation-events`, stores every event idempotently in its own database, and exposes an admin-only paginated read endpoint.

**Architecture:** New Maven modules `slotwise-events` (shared wire contract, plain jar) and `audit-service` (Spring Boot, port 8082). Audit has its own consumer group and its own Postgres database; dedupe is a UNIQUE `event_id` + `INSERT ... ON CONFLICT DO NOTHING`.

**Tech Stack:** Java 25, Spring Boot 4.0.1 (BOM import, no starter-parent), Spring Kafka, Spring Data JPA, Flyway, OAuth2 Resource Server, Testcontainers (Postgres 17, Kafka `apache/kafka:4.1.0`), Lombok.

**Spec:** `docs/superpowers/specs/2026-10-09-audit-service-design.md`

## Global Constraints

- Parent pom imports `spring-boot-dependencies:4.0.1` as BOM; it does **not** extend `spring-boot-starter-parent`. Compiler config (`<parameters>true</parameters>` + Lombok `annotationProcessorPaths`) lives in the parent's `pluginManagement` after Task 1.
- Event id format: `reservation-created:<reservationId>`. Topic: `reservation-events` (constant `ReservationCreatedEvent.TOPIC`).
- Audit consumer: `group-id: audit-service`, `auto-offset-reset: earliest`. Database `slotwise_audit`, own user. Port `8082`.
- Endpoint `GET /api/audit-events`, paginated, optional `?resourceId=`, role `RESERVATION_ADMIN` (`ROLE_RESERVATION_ADMIN` authority from `realm_access.roles`).
- Code conventions (AGENTS.md): explicit `this.`, `final` locals, `@RequiredArgsConstructor`, `@NullMarked` `package-info.java` per package, reads straight into record DTOs via JPQL constructor expression, Flyway owns schema (`ddl-auto: validate`).
- Boot 4 moved many classes to per-feature packages: if an import 404s, search the jar for the class name instead of guessing (see AGENTS.md gotchas).
- Docs travel with code: `decisions.md`, `roadmap.md`, `AGENTS.md` updated (Task 5, before any push).
- Out of scope: Notification/User services, dead-letter topic, row cleanup, Micrometer Tracing, Dockerfiles, schema registry.

## Review Focus

- Poison message (undeserializable bytes) on the topic must not wedge the consumer: a valid event published after it is still stored. (Task 3)
- Duplicate delivery of the same event stores exactly one row. (Task 3)
- `?resourceId=` for a resource with no events returns 200 with an empty page, not 404. (Task 4)
- Requested page size is honoured and ordering is deterministic (`id` descending) so pages don't overlap. (Task 4)
- No token → 401; valid token without `RESERVATION_ADMIN` (e.g. `RESERVATION_USER`) → 403. (Task 4)

---

### Task 1: Parent pom — register modules, hoist compiler config

**Files:**
- Modify: `pom.xml` (add `<module>slotwise-events</module>`, `<module>audit-service</module>`; add `maven-compiler-plugin` with the config below to `pluginManagement`)
- Modify: `booking-service/pom.xml` (remove its `maven-compiler-plugin` block)

**Interfaces:**
- Produces: every module inherits `<parameters>true</parameters>` and Lombok `annotationProcessorPaths` with no per-module config.

- [ ] **Step 1:** Move the `maven-compiler-plugin` `<configuration>` (parameters + lombok path) from `booking-service/pom.xml` into parent `pluginManagement`; add the two `<module>` entries (modules don't exist yet, so don't run Maven until Task 2 creates `slotwise-events`; temporarily comment out `audit-service`).
- [ ] **Step 2:** Run `mvn clean test-compile -pl booking-service -q`. Expected: BUILD SUCCESS (proves Lombok + `-parameters` still apply via inheritance).
- [ ] **Step 3: Commit** `Move compiler plugin config to parent pluginManagement`.

### Task 2: `slotwise-events` module; booking-service consumes it

**Files:**
- Create: `slotwise-events/pom.xml` (jar, parent `slotwise-parent`, dependency `org.jspecify:jspecify` for `@NullMarked`), `slotwise-events/src/main/java/com/slotwise/events/ReservationCreatedEvent.java`, `slotwise-events/src/main/java/com/slotwise/events/package-info.java`
- Delete: `booking-service/src/main/java/com/slotwise/booking/service/ReservationCreatedEvent.java`
- Modify: `booking-service/pom.xml` (dependency on `com.slotwise:slotwise-events:${project.version}`); imports in `ReservationWriteService`, `ReservationEventListener`, `OutboxRelay`, `KafkaTopicConfig`, `ReservationServiceIntegrationTest`; `application.yml` `spring.json.trusted.packages: com.slotwise.events`

**Interfaces:**
- Produces: `public record ReservationCreatedEvent(Long reservationId, Long resourceId, Instant startTime, Instant endTime)` with `public static final String TOPIC = "reservation-events"`, in package `com.slotwise.events`. Keep the existing explanatory comment about why `TOPIC` lives on the message type.

- [ ] **Step 1:** Create the module and move the record (same fields/constant/comment, new package).
- [ ] **Step 2:** Update booking-service imports and `trusted.packages`; add the dependency.
- [ ] **Step 3:** Run `mvn -q -pl slotwise-events,booking-service -am test`. Expected: all existing booking-service tests pass (the outbox→Kafka→listener test proves serialization still round-trips under the new package).
- [ ] **Step 4: Commit** `Extract ReservationCreatedEvent into shared slotwise-events module`.

### Task 3: audit-service persistence + idempotent consumer

**Files:**
- Create: `audit-service/pom.xml` (deps: web, data-jpa, validation, security, oauth2-resource-server, `spring-boot-starter-kafka`, `spring-boot-flyway` + flyway-core + flyway-database-postgresql, postgresql runtime, lombok optional, `slotwise-events`; test: starter-test, `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql`, `testcontainers-kafka`, `spring-boot-starter-kafka-test`, `spring-security-test`; same `spring-boot-maven-plugin` lombok exclude as booking-service)
- Create: `audit-service/src/main/java/com/slotwise/audit/AuditServiceApplication.java`, `package-info.java` for `data/`, `service/`, `model/`, `controller/`, `security/`
- Create: `audit-service/src/main/resources/application.yml`, `db/migration/V1__audit_event.sql`
- Create: `data/AuditEvent.java`, `data/AuditEventRepository.java`, `service/AuditEventListener.java`
- Test: `audit-service/src/test/java/com/slotwise/audit/service/AuditEventListenerIntegrationTest.java`

**Interfaces:**
- Produces: table `audit_event(id BIGINT identity PK, event_id VARCHAR(255) NOT NULL UNIQUE, event_type VARCHAR(100) NOT NULL, resource_id BIGINT NOT NULL, payload TEXT NOT NULL, received_at TIMESTAMPTZ NOT NULL)` with an index on `(resource_id, id DESC)`.
- Produces: `AuditEventRepository.insertIfAbsent(String eventId, String eventType, Long resourceId, String payload): int` — native `INSERT ... ON CONFLICT (event_id) DO NOTHING` returning rows inserted (`@Modifying`).
- Produces: `AuditEventListener.onReservationCreated(ReservationCreatedEvent)` — `@KafkaListener(topics = ReservationCreatedEvent.TOPIC)`, writes event id `reservation-created:<reservationId>`, `event_type` `reservation-created`, payload = event re-serialized with the injected `JsonMapper`.
- `application.yml`: `server.port: 8082`; datasource `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/slotwise_audit` user/password `slotwise_audit`; `ddl-auto: validate`; kafka `bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`, consumer `group-id: audit-service`, `auto-offset-reset: earliest`, `value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer` with `spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer` and `spring.json.trusted.packages: com.slotwise.events` (ErrorHandlingDeserializer turns an undeserializable record into a skippable error instead of an infinite retry loop); producer `value-serializer: ...JsonSerializer` (tests publish with it); `issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8081/realms/slotwise}`.

- [ ] **Step 1: Write failing tests** in `AuditEventListenerIntegrationTest` (`@SpringBootTest(webEnvironment = NONE)`, `@Testcontainers`, `@ServiceConnection` Postgres + `KafkaContainer(apache/kafka:4.1.0)`; inject `KafkaTemplate<String, Object>` and `JdbcClient`; use Awaitility to poll):
  - `publishedEvent_isStored`: publish `ReservationCreatedEvent(1L, 10L, …)` → row with `event_id = 'reservation-created:1'`, `resource_id = 10`.
  - `duplicateEvent_storedOnce`: publish the same event twice, then a marker event with a different id; await the marker row; assert `count(*) WHERE event_id = 'reservation-created:2'` is 1 (the marker proves the duplicate was already consumed).
  - `poisonMessage_doesNotBlockLaterEvents`: send raw bytes `not-json` to the topic with a `KafkaTemplate<String, byte[]>`, then a valid event; await the valid row.
- [ ] **Step 2:** Run `mvn -q -pl audit-service -am test -Dtest=AuditEventListenerIntegrationTest`. Expected: FAIL (context/classes missing).
- [ ] **Step 3:** Implement the module files above. `AuditEvent` entity mirrors `Reservation`'s style (`@Getter @Setter @NoArgsConstructor`, IDENTITY id); the listener calls `insertIfAbsent` and logs "Duplicate audit event skipped" when it returns 0.
- [ ] **Step 4:** Re-run the same command. Expected: 3 tests PASS. If `@ServiceConnection` Kafka or JSON header type resolution misbehaves, check `spring.json.trusted.packages` and that `slotwise-events` is on the classpath.
- [ ] **Step 5: Commit** `audit-service: consume reservation-events into audit_event (idempotent)`.

### Task 4: Read endpoint + security

**Files:**
- Create: `model/AuditEventDto.java`, `service/AuditEventService.java`, `controller/AuditEventController.java`, `controller/GlobalExceptionHandler.java` only if validation errors need mapping (skip otherwise), `security/Roles.java`, `security/SecurityConfig.java`
- Modify: `data/AuditEventRepository.java` (add query)
- Test: `audit-service/src/test/java/com/slotwise/audit/controller/AuditEventControllerTest.java`

**Interfaces:**
- Consumes: `audit_event` table and `AuditEventRepository` from Task 3.
- Produces: `record AuditEventDto(Long id, String eventId, String eventType, Long resourceId, String payload, Instant receivedAt)`.
- Produces: `AuditEventRepository.findSummaries(@Param("resourceId") Long resourceId, Pageable pageable): Page<AuditEventDto>` — JPQL `SELECT new …AuditEventDto(...) FROM AuditEvent e WHERE (:resourceId IS NULL OR e.resourceId = :resourceId) ORDER BY e.id DESC`.
- Produces: `AuditEventService.list(Long resourceId, Pageable pageable): Page<AuditEventDto>` (`@Service @Validated`, `@Transactional(readOnly = true)`).
- Produces: `GET /api/audit-events` with `@PreAuthorize("hasRole('" + Roles.RESERVATION_ADMIN + "')")`, `@PageableDefault(size = 20)`; `Roles` constants copied from booking-service (`RESERVATION_ADMIN`, `RESERVATION_USER`).
- `SecurityConfig`: same shape as booking-service's (stateless, CSRF off, `anyRequest().authenticated()`, JWT converter mapping `realm_access.roles` → `ROLE_*`, `@EnableMethodSecurity`, `@ConditionalOnWebApplication`) **without** the Redisson/rate-limit parts.

- [ ] **Step 1: Write failing tests** in `AuditEventControllerTest` (`@SpringBootTest` + MockMvc from the Boot 4 web-mvc test autoconfigure package — find it in the jar if the import fails — Testcontainers Postgres + Kafka as in Task 3; seed rows through `AuditEventRepository.insertIfAbsent`; Spring Security `jwt()` post-processor with `SimpleGrantedAuthority("ROLE_RESERVATION_ADMIN")`):
  - `admin_listsEventsNewestFirst`: 3 seeded rows → 200, `content[0].eventId` is the newest, `totalElements` 3.
  - `resourceIdFilter_returnsOnlyThatResource`, and `resourceIdWithNoEvents_returnsEmptyPage` (200, `content` empty).
  - `pageSizeHonoured`: `?size=2` → `content.length() == 2`, and `?size=2&page=1` returns the remaining row with no overlap.
  - `noToken_is401`; `reservationUserRole_is403`.
- [ ] **Step 2:** Run `mvn -q -pl audit-service -am test -Dtest=AuditEventControllerTest`. Expected: FAIL.
- [ ] **Step 3:** Implement the interfaces above.
- [ ] **Step 4:** Re-run. Expected: all PASS; then `mvn -q -pl audit-service -am test` for the whole module.
- [ ] **Step 5: Commit** `audit-service: admin-only paginated audit-events endpoint`.

### Task 5: Local infra + docs

**Files:**
- Create: `postgres/audit-init.sh` (creates role `slotwise_audit` with password `slotwise_audit` and database `slotwise_audit` owned by it; idempotent-safe with `ON_ERROR_STOP`)
- Modify: `docker-compose.yml` (mount `audit-init.sh` into the primary's `docker-entrypoint-initdb.d`, with a comment that it only runs on a fresh volume)
- Modify: `docs-site/docs/decisions.md` (new section), `docs-site/docs/roadmap.md` (Fase 4 split progress), `AGENTS.md` (Structure: new modules; Known gotchas: any hit during Tasks 1-4, plus the one-time manual DB creation command for an existing volume)

- [ ] **Step 1:** Write the script and compose mount. Verify the script's SQL by running it against a throwaway container: `docker run --rm -e POSTGRES_PASSWORD=x -v $PWD/postgres/audit-init.sh:/docker-entrypoint-initdb.d/audit-init.sh:ro -d --name audit-init-check postgres:17`, then `docker exec audit-init-check psql -U postgres -lqt` lists `slotwise_audit`; remove the container.
- [ ] **Step 2:** Write the docs: `decisions.md` section "Audit service: first service split" covering shared-module vs copies vs schema registry, isolated DB, UNIQUE-based idempotency, `ErrorHandlingDeserializer` (and that booking-service's listener still lacks it), the JSON class-name header caveat, and what was left out; tick the roadmap; update AGENTS.md.
- [ ] **Step 3:** Run `mvn -q test` at the root. Expected: BUILD SUCCESS across all modules.
- [ ] **Step 4: Commit** `Audit service: local infra and docs`.
