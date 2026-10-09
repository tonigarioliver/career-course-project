package com.slotwise.audit.model;

import java.time.Instant;

public record AuditEventDto(
        Long id, String eventId, String eventType, Long resourceId, String payload, Instant receivedAt) {
}
