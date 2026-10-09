package com.slotwise.notification.data;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // ON CONFLICT DO NOTHING on the UNIQUE event_id is the dedupe (one atomic statement). Returns
    // rows inserted (0 = duplicate).
    @Modifying
    @Query(value = """
            INSERT INTO notification
                (event_id, reservation_id, owner_subject, status, last_error, next_attempt_at, created_at)
            VALUES (:eventId, :reservationId, :ownerSubject, :status, :lastError, now(), now())
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(String eventId, Long reservationId, @Nullable String ownerSubject, String status,
            @Nullable String lastError);

    // SKIP LOCKED: concurrent workers never wait on, or double-claim, the same row.
    @Query(value = """
            SELECT * FROM notification
            WHERE status = 'PENDING' AND next_attempt_at <= now()
            ORDER BY next_attempt_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Notification> lockNextDue();
}
