package com.slotwise.booking.service;

import java.time.Instant;

// Fase 3 "Pub/Sub": the message published to the "reservation-events" Redis channel on every
// successful ReservationService.create(). Deliberately not the full ReservationDto — a Pub/Sub
// message is fire-and-forget with no schema registry/versioning story (unlike a Kafka topic,
// Fase 4), so keeping it to a minimal, stable shape avoids coupling every future subscriber to
// ReservationDto's fields.
public record ReservationCreatedEvent(Long reservationId, Long resourceId, Instant startTime, Instant endTime) {
}
