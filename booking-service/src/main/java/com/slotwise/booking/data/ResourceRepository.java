package com.slotwise.booking.data;

import com.slotwise.booking.model.ResourceDto;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

    @Query("""
            SELECT new com.slotwise.booking.model.ResourceDto(r.id, r.name, r.description, r.active)
            FROM Resource r
            WHERE r.id = :id
            """)
    Optional<ResourceDto> findSummaryById(@Param("id") Long id);

    @Query("SELECT new com.slotwise.booking.model.ResourceDto(r.id, r.name, r.description, r.active) FROM Resource r")
    Page<ResourceDto> findAllSummaries(Pageable pageable);
}
