package com.slotwise.booking.model;

import java.time.Instant;
import com.slotwise.booking.data.ReservationStatus;
import lombok.Builder;

@Builder
public record ReservationDto(
        Long id,
        Long resourceId,
        Instant startTime,
        Instant endTime,
        String ownerSubject,
        ReservationStatus status) {
}
