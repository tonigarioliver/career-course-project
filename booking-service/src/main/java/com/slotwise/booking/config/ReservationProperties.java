package com.slotwise.booking.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "slotwise.reservation")
public record ReservationProperties(@Positive int minDurationMinutes, @Positive int maxDurationMinutes) {
}