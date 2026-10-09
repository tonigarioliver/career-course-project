package com.slotwise.audit.service;

import com.slotwise.audit.data.AuditEventRepository;
import com.slotwise.events.ReservationCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// Own consumer group (application.yml: audit-service), so Audit gets every message independently
// of booking-service's listener. Idempotent: the producer's outbox is at-least-once, so a
// duplicate delivery hits the UNIQUE event_id and is skipped.
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private static final String EVENT_TYPE = "reservation-created";

    private final AuditEventRepository auditEventRepository;
    private final JsonMapper jsonMapper;

    @Transactional
    @KafkaListener(topics = ReservationCreatedEvent.TOPIC)
    void onReservationCreated(final ReservationCreatedEvent event) {
        final int inserted = this.auditEventRepository.insertIfAbsent(
                EVENT_TYPE + ":" + event.reservationId(), EVENT_TYPE, event.resourceId(),
                this.jsonMapper.writeValueAsString(event));
        if (inserted == 0) {
            log.info("Duplicate audit event skipped: {}", event);
        }
    }
}
