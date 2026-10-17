# Slotwise

Booking/reservations platform built as a course project across a backend
learning roadmap (Spring Boot advanced → PostgreSQL → Redis → Kafka → Docker → K8s).

## Stack
Java 25 · Spring Boot 4 · Spring Data JPA · PostgreSQL 17 · Keycloak 26
(OAuth2 Resource Server, JWT) · Testcontainers · Docker Compose

## What it does
- `booking-service` — resources and reservations, secured with Keycloak-issued JWTs mapped to Spring
  Security roles, cached in Redis, rate limited, with a transactional outbox that publishes
  `reservation-created` events to Kafka.
- `audit-service` — consumes those events idempotently into its own database and exposes them to admins.
- `notification-service` — consumes them into an inbox; a worker resolves the owner's email from Keycloak
  and notifies, with configurable retries.
- `slotwise-events` — the shared event contract.

Integration tests run against real Testcontainers (Postgres, Redis, Kafka).

## Architecture

Who talks to whom (blue: services, green: databases, red: Redis, orange: Kafka, purple: Keycloak):

```mermaid
flowchart LR
    client([Client]):::actor

    subgraph identity[Identity]
        kc[Keycloak<br/>OIDC · realm slotwise]:::auth
    end

    subgraph services[Services]
        booking[booking-service<br/>REST API + outbox relay]:::service
        audit[audit-service<br/>:8082]:::service
        notif[notification-service]:::service
    end

    subgraph data[Data]
        pg[(Postgres<br/>primary)]:::db
        replica[(Postgres<br/>replica)]:::db
        redis[(Redis<br/>cache · locks · rate limit)]:::cache
        auditdb[(slotwise_audit)]:::db
        notifdb[(slotwise_notification)]:::db
    end

    kafka{{Kafka<br/>reservation-events}}:::broker

    client -- token --> kc
    client -- JWT --> booking
    booking -- writes --> pg
    booking -- reads --> replica
    pg -. WAL .-> replica
    booking --- redis
    booking -- via outbox --> kafka
    kafka --> audit
    kafka --> notif
    audit --> auditdb
    notif --> notifdb
    notif -- owner email --> kc

    classDef service fill:#dbeafe,stroke:#2563eb,stroke-width:2px,color:#1e3a8a
    classDef db fill:#dcfce7,stroke:#16a34a,stroke-width:2px,color:#14532d
    classDef cache fill:#fee2e2,stroke:#dc2626,stroke-width:2px,color:#7f1d1d
    classDef broker fill:#ffedd5,stroke:#ea580c,stroke-width:2px,color:#7c2d12
    classDef auth fill:#f3e8ff,stroke:#9333ea,stroke-width:2px,color:#581c87
    classDef dead fill:#f1f5f9,stroke:#64748b,stroke-width:2px,stroke-dasharray:5 4,color:#334155
    classDef actor fill:#fef9c3,stroke:#ca8a04,stroke-width:2px,color:#713f12
    classDef group fill:#f8fafc,stroke:#cbd5e1,stroke-width:1px,color:#475569
    class identity,services,data group
```

### Events

A reservation becomes an event through the outbox. Each consumer has its own consumer group, and a record that
keeps failing is parked on the dead-letter topic instead of being lost:

```mermaid
flowchart LR
    tx[Reservation + outbox row<br/>one transaction]:::db
    relay[OutboxRelay<br/>polls and publishes]:::service
    topic{{reservation-events<br/>3 partitions}}:::broker
    audit[audit-service<br/>idempotent insert]:::service
    notif[notification-service<br/>inbox + retrying worker]:::service
    booking[booking-service<br/>own listener]:::service
    dlt{{reservation-events.DLT}}:::dead

    tx --> relay --> topic
    topic --> audit
    topic --> notif
    topic --> booking
    audit -. retries exhausted .-> dlt
    notif -. retries exhausted .-> dlt
    booking -. retries exhausted .-> dlt

    classDef service fill:#dbeafe,stroke:#2563eb,stroke-width:2px,color:#1e3a8a
    classDef db fill:#dcfce7,stroke:#16a34a,stroke-width:2px,color:#14532d
    classDef cache fill:#fee2e2,stroke:#dc2626,stroke-width:2px,color:#7f1d1d
    classDef broker fill:#ffedd5,stroke:#ea580c,stroke-width:2px,color:#7c2d12
    classDef auth fill:#f3e8ff,stroke:#9333ea,stroke-width:2px,color:#581c87
    classDef dead fill:#f1f5f9,stroke:#64748b,stroke-width:2px,stroke-dasharray:5 4,color:#334155
    classDef actor fill:#fef9c3,stroke:#ca8a04,stroke-width:2px,color:#713f12
```

### One reservation, step by step

The request, the outbox and both consumers. One `traceId` follows it all the way:

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant B as booking-service
    participant DB as Postgres
    participant R as OutboxRelay
    participant K as Kafka
    participant A as audit-service
    participant N as notification-service

    C->>B: POST /api/reservations (JWT)
    Note over B: opens a trace (traceId)
    B->>DB: one transaction: reservation + outbox row<br/>(row keeps the traceparent)
    B-->>C: 201 Created
    Note over R: later, another thread
    R->>DB: next unpublished row (FOR UPDATE SKIP LOCKED)
    R->>K: publish ReservationCreatedEvent<br/>(same traceId in the headers)
    R->>DB: mark as published
    par each consumer group
        K->>A: event
        A->>A: INSERT ... ON CONFLICT DO NOTHING<br/>(duplicates skipped)
    and
        K->>N: event
        N->>N: inbox row, worker sends later<br/>(retries, owner email from Keycloak)
    end
    Note over K,N: a record that keeps failing goes to<br/>reservation-events.DLT after its retries
```

## Run locally
```bash
docker compose up -d      # Postgres (+replica), Redis, Kafka, Keycloak (realm auto-imported)
mvn -pl booking-service spring-boot:run
mvn -pl audit-service spring-boot:run          # optional
mvn -pl notification-service spring-boot:run   # optional
```
See `docs-site/docs/getting-started.md` for tokens, example calls and the one-time database setup on an
existing Postgres volume.
