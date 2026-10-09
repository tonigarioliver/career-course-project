---
icon: lucide/play
---

# Getting started (try it locally)

Everything below assumes you've cloned the repo and have Docker + a JDK 25 (see
`.sdkmanrc`) available.

## 1. Start the infrastructure

```bash
docker compose up -d
```

This brings up:

- **Postgres 17** on `localhost:5432` (`slotwise`/`slotwise`/`slotwise`), plus a read replica on `5433`.
  On a **fresh volume** it also creates the `slotwise_audit` and `slotwise_notification` databases
  (`postgres/audit-init.sh`, `postgres/notification-init.sh`). On a volume that already existed, create
  them once by hand:

    ```bash
    docker compose exec postgres bash /docker-entrypoint-initdb.d/audit-init.sh
    docker compose exec postgres bash /docker-entrypoint-initdb.d/notification-init.sh
    ```
- **Redis 7** on `localhost:6379` (cache and rate limiting).
- **Kafka 4.1** (KRaft, single broker) on `localhost:9092`.
- **Keycloak 26** on `localhost:8081` — an OIDC Authorization Server, with a realm
  called `slotwise` auto-imported from `keycloak/realm-export.json` (roles, a public
  client, two test users, and a read-only service account for `notification-service`). After editing
  `keycloak/realm-export.json`, recreate the container so the realm is re-imported:
  `docker compose up -d --force-recreate keycloak`. Admin console: `http://localhost:8081` (`admin`/`admin`).

Wait until `docker compose logs keycloak` shows `Keycloak ... started in ...s` before
continuing — the realm import happens during that startup.

## 2. Start `booking-service`

```bash
mvn -pl booking-service spring-boot:run
```

Listens on `http://localhost:8080`. Every `/api/**` endpoint requires a valid
Keycloak-issued Bearer token — there is no login endpoint in this app, tokens come
from Keycloak directly.

### Optional: start the other services

`booking-service` publishes a `reservation-created` event for every reservation (through a transactional
outbox) to the Kafka topic `reservation-events`. Two more services consume it, each in its own terminal:

```bash
mvn -pl audit-service spring-boot:run          # http://localhost:8082, GET /api/audit-events (RESERVATION_ADMIN)
mvn -pl notification-service spring-boot:run   # no HTTP API; resolves the owner's email from Keycloak and "sends" it (log)
```

Start order doesn't matter: only `booking-service` creates the topic, the consumers wait for it.

## 3. Get a token

Two users are pre-loaded, with different realm roles:

| user    | password   | role                |
|---------|------------|---------------------|
| `alice` | `alice123` | `RESERVATION_ADMIN` |
| `bob`   | `bob123`   | `RESERVATION_USER`  |

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/realms/slotwise/protocol/openid-connect/token \
  -d "client_id=booking-service-client" \
  -d "username=alice" \
  -d "password=alice123" \
  -d "grant_type=password" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
```

## 4. Call the API

Create a resource (requires `RESERVATION_ADMIN` — `alice`'s token):

```bash
curl -s -X POST http://localhost:8080/api/resources \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Sala A","description":"Sala de reuniones, 4 personas"}'
```

Book a reservation (any authenticated user — either `alice` or `bob`'s token). `ownerSubject` is the
owner's Keycloak id, i.e. the token's `sub` claim:

```bash
SUB=$(echo "$TOKEN" | cut -d. -f2 | python3 -c 'import sys,base64,json;p=sys.stdin.read().strip();p+="="*(-len(p)%4);print(json.loads(base64.urlsafe_b64decode(p))["sub"])')

curl -s -X POST http://localhost:8080/api/reservations \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"resourceId\":1,\"startTime\":\"2026-09-01T10:00:00Z\",\"endTime\":\"2026-09-01T11:00:00Z\",\"ownerSubject\":\"$SUB\"}"
```

`notification-service` looks the owner up in Keycloak by that id; an `ownerSubject` that isn't a real Keycloak
user id ends up `FAILED` instead of notified.

### Follow the event

```bash
# audit-service (needs an admin token): every reservation event, newest first
curl -s http://localhost:8082/api/audit-events -H "Authorization: Bearer $TOKEN"

# notification-service: SENT with the owner's email, or FAILED with the reason
docker compose exec postgres psql -U slotwise_notification -d slotwise_notification \
  -c "select event_id,status,attempts,recipient,last_error from notification order by id desc limit 5"
```

List reservations for a resource:

```bash
curl -s http://localhost:8080/api/resources/1/reservations \
  -H "Authorization: Bearer $TOKEN"
```

## Expected authorization behavior

| Request                          | No token | `bob` (`RESERVATION_USER`) | `alice` (`RESERVATION_ADMIN`) |
|-----------------------------------|----------|-----------------------------|--------------------------------|
| `GET /api/resources`              | `401`    | `200`                       | `200`                          |
| `POST /api/resources`             | `401`    | `403`                       | `201`                          |
| `POST /api/reservations`          | `401`    | `201`                       | `201`                          |

See [Decisions](decisions.md) for *why* it's wired this way (issuer discovery, the
Keycloak → Spring role mapping, method security).
