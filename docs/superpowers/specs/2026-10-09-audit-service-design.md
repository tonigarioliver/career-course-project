# Audit Service — design (Fase 4, first service split)

## Intent

First step of splitting `booking-service` into User / Notification / Audit services
communicating via Kafka (Fase 4 project target). Audit goes first because it is the simplest
consumer: it needs no data from any other service.

**Goal:** learn the independent-consumer pattern end to end (own Maven module, own consumer
group, own database, shared wire contract). Audit stores every `reservation-created` event and
exposes a minimal read endpoint, which also makes the service verifiable.

**Decisions taken** (with the alternatives considered):

| Topic | Choice | Rejected |
|---|---|---|
| Scope | Store events + minimal read endpoint | Store-only; full queryable audit trail |
| Storage | Own database `slotwise_audit` + own user, same Postgres instance | Separate container; separate schema (discipline only) |
| Endpoint auth | OAuth2 Resource Server, role `RESERVATION_ADMIN` | Unauthenticated "internal" service |
| Contract sharing | Shared Maven module `slotwise-events` | Per-service copies of the record; schema registry (Avro/JSON Schema) |

## 1. Module structure

```
career-course-project/
├── slotwise-events/     new — plain jar, the wire contract only
├── booking-service/     changed — depends on slotwise-events
└── audit-service/       new — Spring Boot, port 8082
```

- **`slotwise-events`**: `ReservationCreatedEvent` (record + `TOPIC` constant) in package
  `com.slotwise.events`. No Spring/Kafka dependency.
- **`booking-service`**: drops its own copy of the record and imports it from the module.
  `spring.json.trusted.packages` becomes `com.slotwise.events`. `JsonSerializer` writes the
  fully-qualified class name in a header, so messages already in the local topic become
  undeserializable — clear the topic (dev only).
- **`audit-service`**: same package layout and code conventions as `booking-service`
  (`data/`, `model/`, `service/`, `controller/`, `security/`). Depends on `slotwise-events`,
  Web, JPA, Kafka, OAuth2 Resource Server, Flyway, Lombok.
- **Root pom**: the `maven-compiler-plugin` config (Lombok `annotationProcessorPaths` +
  `-parameters`) moves from `booking-service/pom.xml` to the parent's `pluginManagement`, as
  AGENTS.md already suggests, so the new module inherits it instead of re-hitting that gotcha.

## 2. Data flow and persistence

`booking-service` (outbox) → topic `reservation-events` → Audit listener → `audit_event` →
`GET` endpoint.

- **Consumer**: `@KafkaListener` with its own `group-id: audit-service` (receives every
  message independently of booking-service's listener), `auto-offset-reset: earliest`.
- **Table `audit_event`** (Flyway migration owned by audit-service): `id`, `event_id`
  (`reservation-created:<reservationId>`, **UNIQUE**), `event_type`, `resource_id`,
  `payload` (full event JSON), `received_at`.
- **Idempotency**: `INSERT ... ON CONFLICT DO NOTHING` on `event_id`. The audit table itself
  is the processed-events record, so no separate `processed_event` table here. A duplicate
  from the at-least-once outbox is a no-op.
- **Database**: `slotwise_audit` with its own user in the existing Postgres, created by a
  script under `postgres/` mounted into `docker-entrypoint-initdb.d`. That only runs on a
  **fresh volume**; an existing volume needs the database created once by hand (documented).
- **Endpoint**: `GET /api/audit-events`, paginated, optional `?resourceId=`. Reads straight
  into a record DTO via a JPQL constructor expression (project convention: reads skip the
  entity). Requires role `RESERVATION_ADMIN` (already defined in
  `keycloak/realm-export.json` and `Roles`).
- **Failures**: Spring Kafka's default error handler (bounded retries, then log and skip).
  No dead-letter topic yet.

## 3. Tests, compose, docs

**Tests** (Testcontainers Postgres + Kafka, as in booking-service):
- Publish a `ReservationCreatedEvent` → a row appears in `audit_event`.
- Publish the **same event twice** → exactly one row (end-to-end idempotency; closes the gap
  noted in decisions.md where booking-service's listener had nothing observable to count).
- Endpoint: `RESERVATION_ADMIN` JWT → 200 with paginated events; no token → 401; other role →
  403; `?resourceId=` filters.

**docker-compose / infra**: only the Postgres init script changes (creates the audit
database + user). Audit runs via Maven locally like booking-service; no Dockerfiles (Fase 5).

**Docs** (same commit as the code, per the AGENTS.md rule): `decisions.md` section (shared
events module, isolated DB, UNIQUE-based idempotency, what was left out, contract-change
risk), `roadmap.md` progress, `AGENTS.md` structure + any new gotchas.

## Out of scope (recorded as next steps)

Notification and User services, dead-letter topic, cleanup of old `audit_event` rows,
Micrometer Tracing across services, Dockerfiles, schema registry.
