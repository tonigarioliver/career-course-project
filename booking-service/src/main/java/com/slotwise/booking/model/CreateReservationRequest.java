package com.slotwise.booking.model;

import java.time.Duration;
import java.time.Instant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record CreateReservationRequest(
        @NotNull Long resourceId,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        @NotBlank String ownerSubject) {

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
