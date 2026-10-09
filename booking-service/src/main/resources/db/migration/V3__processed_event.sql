-- Fase 4 — idempotent consumers. The outbox is at-least-once, so a consumer can see the same
-- event twice; the listener inserts the event's id here first and skips it if the row exists.
CREATE TABLE processed_event (
    id VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
