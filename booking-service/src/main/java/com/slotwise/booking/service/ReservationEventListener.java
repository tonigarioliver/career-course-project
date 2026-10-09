package com.slotwise.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Fase 4 "Kafka" kickoff: the in-process stand-in for the notification/audit services the
// roadmap's actual project target adds later (split booking-service into User/Notification/
// Audit, talking over Kafka). No groupId here — it inherits application.yml's
// spring.kafka.consumer.group-id ("booking-service"), the same consumer group this whole app
// would use for any other listener; a real Notification/Audit service would run under its own
// group-id instead, so it gets every message independently rather than competing with this
// one for partitions. See decisions.md for the contrast with the Fase 3 Redis Pub/Sub version
// this replaced (no persistence/replay there; a topic's retained log plus a committed offset
// is exactly what closes that gap here).
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventListener {

    private final ProcessedEvents processedEvents;

    // The outbox delivers at-least-once, so the same event can arrive twice (e.g. the relay's
    // commit failed after a successful send). The dedup insert and the processing share one
    // transaction: a processing failure un-marks the event so the redelivery is retried.
    @Transactional
    @KafkaListener(topics = ReservationCreatedEvent.TOPIC)
    void onReservationCreated(final ReservationCreatedEvent event) {
        if (!this.processedEvents.markProcessed("reservation-created:" + event.reservationId())) {
            log.info("Duplicate reservation event skipped: {}", event);
            return;
        }
        log.info("Reservation event received from {}: {}", ReservationCreatedEvent.TOPIC, event);
    }
}
