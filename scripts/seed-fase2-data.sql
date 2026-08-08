-- Fase 2 (PostgreSQL Avanzado) test data — 1,000 resources / 3,000,000 reservations.
--
-- Sequential-per-resource by construction (not purely random): each resource gets 3,000
-- back-to-back reservations with a small random gap between them. A first attempt at pure
-- per-row `random()` timestamps produced natural overlaps (expected — nothing constrained
-- it not to), which then blocked adding an EXCLUDE constraint on the real table. This
-- version guarantees zero overlap within a resource so the exclusion constraint below can
-- actually be created against it, while still giving realistic volume for index/EXPLAIN
-- ANALYZE work.
--
-- Usage (against the docker-compose Postgres, not Testcontainers — this data needs to
-- persist between sessions):
--   docker compose up -d postgres
--   mvn -pl booking-service spring-boot:run   # once, so Flyway (db/migration) creates
--                                              # the schema — then Ctrl-C it
--   docker exec -i career-course-project-postgres-1 psql -U slotwise -d slotwise \
--       < scripts/seed-fase2-data.sql
--
-- Idempotency: none — re-running adds another batch on top. Reset first with:
--   TRUNCATE reservations, resources RESTART IDENTITY CASCADE;
-- (`reservations` is partitioned by HASH(resource_id) since V1__partition_reservations_
-- by_resource_hash.sql — TRUNCATE on the parent cascades to all 8 partitions, no special
-- handling needed here.)

INSERT INTO resources (name, description, active)
SELECT 'Room ' || g, 'Generated room ' || g, true
FROM generate_series(1, 1000) g;

WITH slots AS (
    SELECT
        r.id AS resource_id,
        n,
        (15 + floor(random() * 465))::int AS duration_min,
        (5 + floor(random() * 115))::int AS gap_min
    FROM resources r
    CROSS JOIN generate_series(1, 3000) AS n
    WHERE r.id <= 1000  -- only the freshly-inserted resources, in case this script is re-run
),
offsets AS (
    SELECT
        resource_id,
        duration_min,
        SUM(gap_min + duration_min) OVER (PARTITION BY resource_id ORDER BY n) AS end_offset_min
    FROM slots
)
INSERT INTO reservations (resource_id, start_time, end_time, owner_subject, status)
SELECT
    resource_id,
    timestamp '2024-01-01' + (end_offset_min - duration_min) * interval '1 minute',
    timestamp '2024-01-01' + end_offset_min * interval '1 minute',
    'user-' || (1 + floor(random() * 10000))::int,
    CASE WHEN random() < 0.9 THEN 'CONFIRMED' ELSE 'CANCELLED' END
FROM offsets;

ANALYZE resources;
ANALYZE reservations;
