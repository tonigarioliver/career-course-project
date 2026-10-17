package com.slotwise.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// slotwise.kafka.retry.*: how often a failing record is retried before it goes to the dead-letter topic.
// Defaults: retries after 1s, 2s, 4s, 8s. A test overrides them to keep a retryable failure fast.
@ConfigurationProperties("slotwise.kafka.retry")
public record KafkaRetryProperties(
        @DefaultValue("4") int maxAttempts,
        @DefaultValue("1s") Duration initialInterval,
        @DefaultValue("2.0") double multiplier) {
}
