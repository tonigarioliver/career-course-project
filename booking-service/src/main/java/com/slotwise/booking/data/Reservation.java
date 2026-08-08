package com.slotwise.booking.data;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Table DDL (partitioning, the composite PK it requires, indexes, the no-overlap EXCLUDE
// constraint) lives in db/migration/V1__partition_reservations_by_resource_hash.sql —
// Flyway owns this table's schema, ddl-auto is validate-only. See decisions.md
// "Partitioning reservations" and "Indexing findOverlapping".
@Entity
@Table(name = "reservations")
@Getter
@Setter
@NoArgsConstructor
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_id", nullable = false)
    private Resource resource;

    @Column(nullable = false)
    private Instant startTime;

    @Column(nullable = false)
    private Instant endTime;

    @Column(nullable = false)
    private String ownerSubject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status = ReservationStatus.CONFIRMED;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Reservation other)) {
            return false;
        }
        return this.id != null && this.id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return this.getClass().hashCode();
    }
}
