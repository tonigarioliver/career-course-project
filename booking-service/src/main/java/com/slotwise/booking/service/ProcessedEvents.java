package com.slotwise.booking.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// Dedup store for at-least-once consumers (see V3__processed_event.sql). No entity: the only
// operation is one atomic INSERT ... ON CONFLICT DO NOTHING, which is the dedupe check itself —
// a separate "exists?" query followed by an insert would race between two concurrent deliveries.
// Joins the caller's transaction, so if processing throws, the marker rolls back too and the
// redelivered event is processed again instead of being dropped.
@Component
@RequiredArgsConstructor
public class ProcessedEvents {

    private final JdbcClient jdbcClient;

    // true = first time this id is seen (go ahead and process), false = duplicate (skip).
    public boolean markProcessed(final String eventId) {
        return this.jdbcClient.sql("INSERT INTO processed_event (id) VALUES (:id) ON CONFLICT DO NOTHING")
                .param("id", eventId)
                .update() == 1;
    }
}
