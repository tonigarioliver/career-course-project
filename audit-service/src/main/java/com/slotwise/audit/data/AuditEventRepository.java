package com.slotwise.audit.data;

import com.slotwise.audit.model.AuditEventDto;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    // ON CONFLICT DO NOTHING on the UNIQUE event_id is the dedupe itself: one atomic statement, so
    // two concurrent deliveries of the same event can't both pass an "exists?" check. Returns the
    // number of rows inserted (0 = duplicate).
    @Modifying
    @Query(value = """
            INSERT INTO audit_event (event_id, event_type, resource_id, payload, received_at)
            VALUES (:eventId, :eventType, :resourceId, :payload, now())
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(String eventId, String eventType, Long resourceId, String payload);

    // ORDER BY id DESC: newest first and deterministic, so consecutive pages never overlap.
    @Query("""
            SELECT new com.slotwise.audit.model.AuditEventDto(
                e.id, e.eventId, e.eventType, e.resourceId, e.payload, e.receivedAt)
            FROM AuditEvent e
            WHERE (:resourceId IS NULL OR e.resourceId = :resourceId)
            ORDER BY e.id DESC
            """)
    Page<AuditEventDto> findSummaries(@Nullable Long resourceId, Pageable pageable);
}
