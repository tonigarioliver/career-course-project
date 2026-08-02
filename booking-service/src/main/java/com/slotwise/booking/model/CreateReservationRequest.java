package com.slotwise.booking.model;

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

    public boolean hasValidTimeRange() {
        return startTime.isBefore(endTime);
    }
}
