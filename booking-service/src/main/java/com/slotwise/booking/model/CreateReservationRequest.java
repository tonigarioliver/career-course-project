package com.slotwise.booking.model;

import java.time.Duration;
import java.time.Instant;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

// ownerSubject is optional: omitted or blank means "the caller"; naming someone else is only allowed to a
// RESERVATION_ADMIN (ReservationController resolves it before the service sees the request).
@Builder(toBuilder = true)
public record CreateReservationRequest(
        @NotNull Long resourceId,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        @Nullable String ownerSubject) {

    public CreateReservationRequest {
        // ponytail: null-guarded because this runs on every construction, including
        // Jackson's JSON deserialization — before @NotNull/@Valid ever get a chance to run.
        if (startTime != null && endTime != null && !startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("startTime must be before endTime");
        }
    }

    public long durationMinutes() {
        return Duration.between(startTime, endTime).toMinutes();
    }
}
