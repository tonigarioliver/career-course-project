# Notification Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (native, inline). Steps use checkbox (`- [ ]`) syntax. Project rule (user preference): work on `main`, **no commits until the user has reviewed**.

> **Executed with deviations** (see decisions.md): transactions ended up in `NotificationWriteService` (no `TransactionTemplate`), the Keycloak client is a declarative `@HttpExchange` interface, and review added the exhausted-lease rule and `markSent` outside the try. Kept as the original plan.

**Goal:** `notification-service` consumes `reservation-created`, stores it as a `PENDING` inbox row, and a worker resolves the owner's email from Keycloak and "sends" it, with configurable retries.

**Architecture:** Inbox pattern. Listener = idempotent insert (UNIQUE `event_id`). Worker = claim (short tx, lease in `next_attempt_at`, `FOR UPDATE SKIP LOCKED`) → Keycloak lookup + send outside any tx → close (short tx). Same module/package conventions as `audit-service`.

**Tech Stack:** Java 25, Spring Boot 4.0.1, Spring Kafka (Jackson-3 serializers), Spring Data JPA, Flyway, `RestClient`, Testcontainers (Postgres 17, Kafka `apache/kafka:4.1.0`), JDK `HttpServer` as Keycloak stub.

**Spec:** `docs/superpowers/specs/2026-10-09-notification-service-design.md`

## Global Constraints

- No port (no HTTP server); DB `slotwise_notification`, user/password `slotwise_notification`; consumer group `notification-service`, `auto-offset-reset: earliest`, `ErrorHandlingDeserializer` + `JacksonJsonDeserializer`, `spring.json.trusted.packages: com.slotwise.events`.
- Event id `reservation-created:<reservationId>`. States `PENDING`/`SENT`/`FAILED`.
- `NotificationProperties(maxAttempts=5, backoff=30s, claimTimeout=60s)` from yaml prefix `slotwise.notification`, registered by a dedicated `@Configuration`.
- Keycloak: realm `slotwise`, client `notification-service-client` (service account, role `view-users`), lookup `GET /admin/realms/slotwise/users/{subject}`; 404 or no email = permanent failure, network/5xx = transient.
- Conventions (AGENTS.md): explicit `this.`, `final` locals, `@RequiredArgsConstructor`, `@NullMarked` `package-info.java`, Flyway owns schema, Boot-4 per-feature packages (search the jar if an import 404s).
- No HTTP API, no security in notification-service. Docs (`decisions.md`, `roadmap.md`, `AGENTS.md`) updated in the same change set. **No commits.**

## Review Focus

- A claimed row must not be picked by a second worker before the lease expires. (T4)
- Legacy event without `ownerSubject` → `FAILED`, never retried. (T2)
- A user without email is permanent (`FAILED` at once), a 5xx is transient (retried). (T3, T4)
- Attempts stop exactly at `maxAttempts`. (T4)
- Same event delivered twice → one row. (T2)

---

### Task 1: Event carries `ownerSubject`

**Files:** Modify `slotwise-events/.../ReservationCreatedEvent.java`, `booking-service/.../ReservationWriteService.java`, `booking-service/src/test/.../ReservationServiceIntegrationTest.java`, `audit-service/src/test/.../AuditEventListenerIntegrationTest.java`.

**Interfaces:** Produces `record ReservationCreatedEvent(Long reservationId, Long resourceId, String ownerSubject, Instant startTime, Instant endTime)` (ownerSubject nullable for legacy messages; declare with `@Nullable`).

- [ ] **Step 1 (test first):** in `create_publishesReservationCreatedEvent` also assert `event.ownerSubject()` equals the request's `"user-kafka"`. Run `mvn -q -pl booking-service -am test -Dtest=ReservationServiceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compile FAIL (no accessor).
- [ ] **Step 2:** add the field to the record; pass `request.ownerSubject()` in `ReservationWriteService`; fix the audit test's constructor call.
- [ ] **Step 3:** run booking + audit modules' tests: `mvn -q -pl slotwise-events,booking-service,audit-service -am test`. Expected: all PASS.

### Task 2: notification-service skeleton, inbox table, listener

**Files:** Create `notification-service/pom.xml` (like audit's minus web/security/validation/oauth2/webmvc-test/security-test; plus `spring-boot-starter-validation`; add module to root `pom.xml`), `NotificationServiceApplication` (`@EnableScheduling`), `package-info.java` per package, `application.yml`, `db/migration/V1__notification.sql`, `data/Notification.java`, `data/NotificationStatus.java`, `data/NotificationRepository.java`, `config/NotificationProperties.java`, `config/NotificationConfig.java`, `service/NotificationListener.java`. Test: `NotificationListenerIntegrationTest`.

**Interfaces:** Produces table `notification(id identity PK, event_id UNIQUE NOT NULL, reservation_id BIGINT NOT NULL, owner_subject VARCHAR NULL, status VARCHAR(20) NOT NULL, attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL, last_error TEXT, recipient VARCHAR, created_at TIMESTAMPTZ NOT NULL, sent_at TIMESTAMPTZ)` + partial index on `next_attempt_at WHERE status='PENDING'`; `NotificationRepository.insertIfAbsent(eventId, reservationId, ownerSubject, status): int` (native `ON CONFLICT (event_id) DO NOTHING`); `NotificationProperties(int maxAttempts, Duration backoff, Duration claimTimeout)`.

- [ ] **Step 1 (tests first):** `eventStored_asPending` (event with owner → row `PENDING`, `attempts=0`); `sameEventTwice_oneRow` (publish A, A, then marker B; await B and assert one row for A); `legacyEventWithoutOwner_isFailed` (`ownerSubject=null` → status `FAILED`, `last_error` mentions missing owner). Run; expected FAIL (no table).
- [ ] **Step 2:** implement. Listener: `@Transactional @KafkaListener(topics = TOPIC)`; status = `FAILED` if `ownerSubject` null/blank else `PENDING`; `next_attempt_at = now()`.
- [ ] **Step 3:** run the test class; expected 3 PASS.

### Task 3: `KeycloakUserClient`

**Files:** Create `service/KeycloakUserClient.java`, `service/KeycloakProperties.java` (record: `baseUrl`, `realm`, `clientId`, `clientSecret`; yaml prefix `slotwise.keycloak`, registered in `NotificationConfig`), `service/PermanentNotificationException.java`. Test: `KeycloakUserClientTest` (JDK `com.sun.net.httpserver.HttpServer` stub).

**Interfaces:** Produces `String KeycloakUserClient.findEmail(String subject)` — returns the email; throws `PermanentNotificationException` on 404 or missing/blank email; throws a plain `IllegalStateException`/`RestClientException` on network error or 5xx (transient). Token via `POST {baseUrl}/realms/{realm}/protocol/openid-connect/token` (`grant_type=client_credentials`) on every call.

- [ ] **Step 1 (tests first):** stub serves the token endpoint and `/admin/realms/slotwise/users/{id}`. `ok_returnsEmail`; `notFound_isPermanent`; `noEmail_isPermanent`; `serverError_isTransient` (not a `PermanentNotificationException`). Expected: FAIL.
- [ ] **Step 2:** implement with `RestClient`. **Step 3:** run; 4 PASS.

### Task 4: Worker and sender

**Files:** Create `service/NotificationSender.java` (`void send(String recipient, Notification n)` — logs), `service/NotificationWorker.java`. Test: `NotificationWorkerTest` (`@SpringBootTest`, Postgres Testcontainers, `@MockitoBean KeycloakUserClient`, Kafka listener auto-startup off, props: `maxAttempts=3, backoff=0s` or small, `claimTimeout=2s`; scheduler disabled in the test via a property so tests call `processNext()` directly).

**Interfaces:** Produces `boolean NotificationWorker.processNext()` — claims one due row and processes it, returns false when nothing due; `@Scheduled(fixedDelayString = "${slotwise.notification.poll-interval:1s}") void poll()` loops `processNext()` until false. Claim = `TransactionTemplate` tx: `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1` (`NotificationRepository.lockNextDue()`), set `next_attempt_at = now()+claimTimeout`; close = second tx.

- [ ] **Step 1 (tests first):** `success_marksSentWithRecipient`; `transientFailure_addsAttemptAndDelays_staysPending`; `reachingMaxAttempts_marksFailed` (3 transient failures → `FAILED`, `attempts=3`); `permanentFailure_failsImmediately` (1 attempt); `claimedRow_notReclaimedBeforeLeaseExpires` (claim a row while Keycloak mock blocks on a latch in another thread; second `processNext()` returns false; release). Expected: FAIL.
- [ ] **Step 2:** implement. **Step 3:** run `mvn -q -pl notification-service -am test`; all PASS.

### Task 5: Infra, docs, real run

**Files:** Modify `postgres/audit-init.sh` → keep, create `postgres/notification-init.sh` (role+db `slotwise_notification`); mount in `docker-compose.yml`; `keycloak/realm-export.json` (client `notification-service-client`: `publicClient=false`, `serviceAccountsEnabled=true`, `clientAuthenticatorType=client-secret`, `secret`, service-account user with `realm-management` role `view-users` — Keycloak realm import syntax: `users` entry `service-account-notification-service-client` with `serviceAccountClientId` and `clientRoles: {"realm-management": ["view-users"]}`); docs per spec; `AGENTS.md`.

- [ ] **Step 1:** write infra + docs. **Step 2:** `mvn -q test` all modules green.
- [ ] **Step 3 (real run):** `docker compose up -d --force-recreate keycloak`, create the notification DB on the existing volume, start booking/audit/notification with `mvn -pl <m> spring-boot:run`, get a token for alice, create a resource and a reservation with `ownerSubject` = alice's `sub`, observe: outbox → Kafka → audit row + notification `SENT` with `recipient=alice@slotwise.local`; then a reservation with an unknown owner → `FAILED`. Stop the services afterwards.
