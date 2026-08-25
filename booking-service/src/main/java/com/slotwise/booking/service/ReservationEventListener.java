package com.slotwise.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// Fase 3 "Pub/Sub": the boundary between "a reservation was created" (a Spring
// ApplicationEvent, published by ReservationService, in-process) and the outside world
// (a message on the "reservation-events" Redis channel). Listens via Spring's own event bus
// with AFTER_COMMIT: this only runs once create()'s transaction actually commits, so a
// reservation that rolled back never gets a Redis message about it.
//
// This app has no subscriber of its own for "reservation-events" — deliberately, since a
// same-process subscriber would just be listening to itself. Standing in for the real
// consumer (a notification/audit service, Fase 4/Kafka), ReservationServiceIntegrationTest's
// pub/sub test plays that role: subscribes, publishes, asserts receipt. Also the roadmap
// item's own point either way — "Pub/Sub and its limitations": no persistence, no replay, no
// consumer groups. A subscriber that isn't up when this fires simply never sees the message,
// unlike a Kafka topic's retained log (Fase 4). See decisions.md for the full contrast.
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventListener {

    static final String CHANNEL = "reservation-events";

    private final RedissonClient redissonClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationCreated(final ReservationCreatedEvent event) {
        this.redissonClient.getTopic(CHANNEL).publish(event);
        log.info("Published reservation event to {}: {}", CHANNEL, event);
    }
}
