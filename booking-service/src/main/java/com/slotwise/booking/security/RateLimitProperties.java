package com.slotwise.booking.security;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "slotwise.rate-limit")
public record RateLimitProperties(@Positive int permitsPerMinute) {
}
