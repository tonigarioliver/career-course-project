package com.slotwise.events;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

// Fase 4 "Kafka": the message published to the TOPIC below on every successful
// ReservationService.create() (Fase 3's Redis Pub/Sub version before it). Deliberately not
// the full ReservationDto — no schema-versioning story to lean on if that DTO's shape changes
// later, so kept to a minimal, stable shape.
//
// TOPIC lives here, not on ReservationService: this is the one thing both the producer
// (ReservationService) and every consumer (ReservationEventListener,
// ReservationServiceIntegrationTest, KafkaTopicConfig's NewTopic declaration) actually need
// to agree on, so it belongs to the message type itself rather than being borrowed from
// whichever class happens to publish it.
//
// ownerSubject is @Nullable only because events published before it existed have none (Jackson
// deserializes the missing field as null); consumers that need it must treat null as "unknown owner".
public record ReservationCreatedEvent(
        Long reservationId, Long resourceId, @Nullable String ownerSubject, Instant startTime, Instant endTime) {

    public static final String TOPIC = "reservation-events";
}
