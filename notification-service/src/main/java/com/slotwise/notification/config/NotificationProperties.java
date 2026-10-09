package com.slotwise.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// maxAttempts: tries before a row becomes FAILED. backoff: delay after a transient failure.
// claimTimeout: how long a worker's claim on a row lasts before another worker may retry it.
@ConfigurationProperties(prefix = "slotwise.notification")
public record NotificationProperties(int maxAttempts, Duration backoff, Duration claimTimeout) {
}
