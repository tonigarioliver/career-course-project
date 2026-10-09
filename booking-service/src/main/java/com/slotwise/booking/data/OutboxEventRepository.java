package com.slotwise.booking.data;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    // SKIP LOCKED: if several instances run the relay, each grabs a different row instead of
    // blocking on (or double-publishing) one another instance already holds.
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<OutboxEvent> lockNextUnpublished();
}
