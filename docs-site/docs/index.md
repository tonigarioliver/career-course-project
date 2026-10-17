---
icon: lucide/rocket
---

# Slotwise — Career Course Project

Booking/reservations platform used as the hands-on companion to a backend-senior
roadmap: Spring Boot avanzado → PostgreSQL internals → Redis → Kafka → Docker →
Kubernetes.

This site tracks three things as the course progresses:

- **[Getting started](getting-started.md)** — clone it, run it, hit the API locally.
- **[Roadmap](roadmap.md)** — what's done, what's pending, phase by phase.
- **[Decisions](decisions.md)** — non-obvious bugs and design calls worth remembering,
  so a future session (or future me) doesn't re-debug the same thing twice.

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

!!! note "Source of truth"

    The code lives in `booking-service/`, `audit-service/`, `notification-service/` and the shared
    `slotwise-events/`. This site is a readable view on top of it —
    if the two ever disagree, the code and the git history win.
