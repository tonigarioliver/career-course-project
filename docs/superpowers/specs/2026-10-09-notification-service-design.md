# Notification Service — design (Fase 4, second service split)

## Intent

Second service of the split (after Audit). Notification turns a `reservation-created` event
into a notification to the reservation's owner. It must work without owning user data: the
recipient's email is resolved from Keycloak at send time. This replaces the originally planned
User Service for this purpose (see decisions below); the User Service is deferred.

**Goal:** learn the Inbox pattern end to end — receive fast and idempotently, process
asynchronously with retries, and handle an external side effect (Keycloak lookup + send) that
can fail or be duplicated.

## Decisions taken (with the alternatives rejected)

| Topic | Choice | Rejected, and why |
|---|---|---|
| Recipient resolution | Inbox row `PENDING`, worker resolves the owner's email from Keycloak at send time | Email inside the event (PII in Kafka, requires owner == caller); local copy of users fed by a User Service (needs sync, has an eventual-consistency race); synchronous REST call inside the consumer (couples consumption to Keycloak) |
| Keycloak access | Pull, read-only service account (`notification-service-client`, role `view-users`) | Push via a Keycloak event-listener SPI (separate project, version upkeep) |
| Retries | `maxAttempts`, `backoff`, `claimTimeout` from yaml via a `@ConfigurationProperties` record | Hard-coded values; exponential backoff (out of scope) |
| User Service | **Deferred** — not needed to notify | Building it now just to hold data Keycloak already owns |
| HTTP API | None (consumer + worker only) | A read endpoint nobody needs yet |

## 1. Structure and flow

- New module `notification-service` (no HTTP, so no port), own database `slotwise_notification` with its
  own user (same Postgres instance, created by an extended script under `postgres/`), own
  consumer group `notification-service`. Depends on `slotwise-events`.
- **Contract change:** `ReservationCreatedEvent` gains `ownerSubject` (needed to look the user up).
  `booking-service` fills it from the request when it writes the outbox event. Events produced
  before this change have no `ownerSubject`: Notification marks them `FAILED` instead of retrying.
  Audit is unaffected (extra JSON field).
- **Flow:**
  1. *Receive:* the listener does `INSERT ... ON CONFLICT DO NOTHING` of a `PENDING` row keyed by
     `event_id` (`reservation-created:<reservationId>`, UNIQUE — same idempotency as Audit) and returns.
  2. *Process:* a `@Scheduled` worker takes a due row, asks Keycloak for the owner's email,
     "sends" the notification (simulated), marks it `SENT`.
  3. *Failure:* a transient failure adds an attempt and delays the row by `backoff`; reaching
     `maxAttempts` makes it `FAILED`. A missing user or a user without email is permanent: `FAILED`
     at once.
- States: `PENDING`, `SENT`, `FAILED`.

## 2. Table, worker, Keycloak

- **Table `notification`** (Flyway): `id`, `event_id` UNIQUE, `reservation_id`, `owner_subject`
  (nullable, for legacy events), `status`, `attempts`, `next_attempt_at`, `last_error`,
  `recipient`, `created_at`, `sent_at`. Partial index on `next_attempt_at` where `status = 'PENDING'`.
- **Worker, no transaction held across HTTP calls:**
  1. *Claim* (short tx): `SELECT ... WHERE status = 'PENDING' AND next_attempt_at <= now()
     ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED LIMIT 1`; in the same tx push
     `next_attempt_at` forward by `claimTimeout` (a lease — no `PROCESSING` state) and commit.
  2. *Work* outside any transaction: Keycloak lookup, then send.
  3. *Close* (short tx): `SENT`, or attempts + 1 and `next_attempt_at = now() + backoff`, or
     `FAILED` (max attempts / permanent error).
  If the process dies during step 2 the lease expires and another worker retries: at-least-once,
  so a notification can be sent twice in that case (documented). A row that comes back after its lease
  with its attempts already used up is failed at claim time (`Lease expired after max attempts`), so a
  worker that keeps dying can't retry forever. `markSent` runs outside the try that classifies send
  failures: if only recording the success fails it propagates (the lease retries) instead of being
  recorded as a failed send.
- **Transactions** live in `NotificationWriteService` (`claimNextDue`, `markSent`, `markFailed`,
  `recordTransientFailure`, public `@Transactional`); `NotificationWorker` is the non-transactional
  orchestrator, the project's `ReservationService`/`ReservationWriteService` split. No `TransactionTemplate`.
- **`NotificationProperties`** record: `maxAttempts` (default 5), `backoff` (30s), `claimTimeout`
  (60s); own `@Configuration` registering it, like `ReservationProperties`.
- **`KeycloakUserClient`** over a declarative `@HttpExchange` interface (`KeycloakApi`, `RestClient` via
  `spring-boot-starter-restclient`, no web server): client-credentials token request, then
  `GET /admin/realms/slotwise/users/{subject}`. 404 or no email → permanent failure; network
  error or 5xx → transient. Token requested on every attempt (simple; caching deferred).
  `realm-export.json` gains the service-account client; re-importing needs
  `docker compose up -d --force-recreate keycloak`.
- **`NotificationSender`**: simulated (log + fills `recipient`), no interface until a second provider exists.

## 3. Tests, docs, scope

**Tests** (Testcontainers Postgres + Kafka as in Audit):
- Listener: event → `PENDING` row; same event twice → one row; event without `ownerSubject` → `FAILED`.
- Worker (real code; only `KeycloakUserClient` replaced): success → `SENT` + `recipient`; transient
  failure → attempts + 1, `next_attempt_at` pushed, still `PENDING`; hitting `maxAttempts` → `FAILED`;
  permanent failure → `FAILED` without retries; a claimed row is not re-claimed before the lease expires.
- `KeycloakUserClient` against a JDK `HttpServer` stub: 200 → email, 404 → permanent, 5xx → transient.
- `booking-service`: the existing outbox→Kafka test also asserts `ownerSubject` in the published event.
- Retry properties use tiny values in tests.

**Docs** (same commit as the code): `decisions.md` (Inbox pattern, the rejected alternatives above,
lease in `next_attempt_at`, permanent vs transient), `roadmap.md` (Notification done, User Service
deferred with the reason), `AGENTS.md` (new module, Keycloak client, `force-recreate` note), plus
the `postgres/` script creating the Notification database.

**Out of scope:** real email provider, exponential backoff, token caching, dead-letter topic,
User Service, a read API for notifications.
