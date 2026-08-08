-- Fase 2 (PostgreSQL Avanzado) test data — 1,000 resources / 3,000,000 reservations.
--
-- Usage (against the docker-compose Postgres, not Testcontainers — this data needs to
-- persist between sessions):
--   docker compose up -d postgres
--   mvn -pl booking-service spring-boot:run   # once, just to let Hibernate (ddl-auto=update)
--                                              # create the schema, then Ctrl-C it
--   docker exec -i career-course-project-postgres-1 psql -U slotwise -d slotwise < scripts/seed-fase2-data.sql
--
-- Idempotency: none on purpose — re-running adds another 3M rows on top. Truncate first
-- (`TRUNCATE reservations, resources RESTART IDENTITY CASCADE;`) if you want a clean reset.

INSERT INTO resources (name, description, active)
SELECT 'Room ' || g, 'Generated room ' || g, true
FROM generate_series(1, 1000) g;

INSERT INTO reservations (resource_id, start_time, end_time, owner_subject, status)
SELECT
    (1 + floor(random() * 1000))::bigint,
    ts,
    ts + (15 + floor(random() * 465))::int * interval '1 minute',
    'user-' || (1 + floor(random() * 10000))::int,
    CASE WHEN random() < 0.9 THEN 'CONFIRMED' ELSE 'CANCELLED' END
FROM (
    SELECT timestamp '2024-01-01' + (random() * 730) * interval '1 day'
                                   + (floor(random() * 1440))::int * interval '1 minute' AS ts
    FROM generate_series(1, 3000000)
) t;

ANALYZE resources;
ANALYZE reservations;
