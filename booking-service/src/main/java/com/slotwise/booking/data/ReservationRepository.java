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

    @Query("""
            SELECT new com.slotwise.booking.model.ReservationDto(
                r.id, r.resource.id, r.startTime, r.endTime, r.ownerSubject, r.status)
            FROM Reservation r
            WHERE r.resource.id = :resourceId
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
