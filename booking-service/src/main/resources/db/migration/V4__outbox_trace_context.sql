-- W3C `traceparent` of the request that wrote the row. OutboxRelay publishes later, from another thread, when that
-- request is long gone: without this, the Kafka span would start a new trace instead of continuing the request's.
-- Null for rows written before this column (or without an active trace).
ALTER TABLE outbox_event ADD COLUMN trace_context VARCHAR(255);
