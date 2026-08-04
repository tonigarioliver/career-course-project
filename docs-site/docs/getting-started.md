---
icon: lucide/play
---

# Getting started (try it locally)

Everything below assumes you've cloned the repo and have Docker + a JDK 25 (see
`.sdkmanrc`) available.

## 1. Start Postgres and Keycloak

```bash
docker compose up -d
```

This brings up:

- **Postgres 17** on `localhost:5432` (`slotwise`/`slotwise`/`slotwise`).
- **Keycloak 26** on `localhost:8081` — an OIDC Authorization Server, with a realm
  called `slotwise` auto-imported from `keycloak/realm-export.json` (roles, a public
  client, two test users). Admin console: `http://localhost:8081` (`admin`/`admin`).

Wait until `docker compose logs keycloak` shows `Keycloak ... started in ...s` before
continuing — the realm import happens during that startup.

## 2. Start `booking-service`

```bash
mvn -pl booking-service spring-boot:run
```

Listens on `http://localhost:8080`. Every `/api/**` endpoint requires a valid
Keycloak-issued Bearer token — there is no login endpoint in this app, tokens come
from Keycloak directly.

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

Book a reservation (any authenticated user — either `alice` or `bob`'s token):

```bash
curl -s -X POST http://localhost:8080/api/reservations \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"resourceId":1,"startTime":"2026-09-01T10:00:00Z","endTime":"2026-09-01T11:00:00Z","ownerSubject":"alice"}'
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
