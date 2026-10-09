package com.slotwise.audit.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// DDL in db/migration/V1__audit_event.sql. Rows are only ever created through
// AuditEventRepository.insertIfAbsent (idempotent native insert); the entity exists so
// Hibernate validates the mapping and JPQL can read it.
@Entity
@Table(name = "audit_event")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String eventId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private Long resourceId;

    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private Instant receivedAt;
}
