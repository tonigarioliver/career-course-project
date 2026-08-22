# Slotwise

Booking/reservations platform built as a course project across a backend
learning roadmap (Spring Boot advanced → PostgreSQL → Redis → Kafka → Docker → K8s).

## Stack
Java 25 · Spring Boot 4 · Spring Data JPA · PostgreSQL 17 · Keycloak 26
(OAuth2 Resource Server, JWT) · Testcontainers · Docker Compose

## What it does
A `booking-service` managing resources and reservations, secured with
Keycloak-issued JWTs mapped to Spring Security roles, with a `@ControllerAdvice`
global exception handler and integration tests running against a real
Testcontainers Postgres instance.

## Run locally
```bash
docker-compose up -d      # Postgres + Keycloak (realm auto-imported)
mvn -pl booking-service spring-boot:run
```
