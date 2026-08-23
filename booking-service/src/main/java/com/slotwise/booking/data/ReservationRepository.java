package com.slotwise.booking.data;

import com.slotwise.booking.model.ReservationDto;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    // ORDER BY matters for more than determinism: it also matches idx_reservations_resource_time's
    // leading columns, so the planner can satisfy the filter *and* the order off that one index. Without
    // it, LIMIT-without-ORDER-BY costing assumes matches are spread uniformly through the table — false
    // here since rows are physically clustered by resource_id (insertion order) — and for some resource_id
    // values (e.g. 500, in partition p3) the planner picked a Seq Scan expecting an early exit, then had
    // to walk ~150k rows to find 20 matches: 9.6ms/1548 buffers vs 0.1ms/16 buffers with this ORDER BY.
    @Query("""
            SELECT new com.slotwise.booking.model.ReservationDto(
                r.id, r.resource.id, r.startTime, r.endTime, r.ownerSubject, r.status)
            FROM Reservation r
            WHERE r.resource.id = :resourceId
            ORDER BY r.startTime, r.id
            """)
    Page<ReservationDto> findSummariesByResourceId(@Param("resourceId") Long resourceId, Pageable pageable);

    @Query("""
            SELECT r FROM Reservation r
            WHERE r.resource.id = :resourceId
              AND r.status <> com.slotwise.booking.data.ReservationStatus.CANCELLED
              AND r.startTime < :endTime
              AND r.endTime > :startTime
            """)
    List<Reservation> findOverlapping(
            @Param("resourceId") Long resourceId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);
}
