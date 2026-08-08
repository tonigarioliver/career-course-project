package com.slotwise.booking.data;

import com.slotwise.booking.model.ResourceDto;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

    @Query("""
            SELECT new com.slotwise.booking.model.ResourceDto(r.id, r.name, r.description, r.active)
            FROM Resource r
            WHERE r.id = :id
            """)
    Optional<ResourceDto> findSummaryById(@Param("id") Long id);

    // Renders as `SELECT ... FOR UPDATE`: serializes concurrent ReservationService.create()
    // calls for the same resource, so the second caller's overlap check runs against the
    // first caller's already-committed row instead of racing it (see decisions.md
    // "Indexing findOverlapping" follow-up for the reproduced race and the fix).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Resource r WHERE r.id = :id")
    Optional<Resource> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT new com.slotwise.booking.model.ResourceDto(r.id, r.name, r.description, r.active) FROM Resource r")
    Page<ResourceDto> findAllSummaries(Pageable pageable);
}
