package com.slotwise.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.slotwise.events.ReservationCreatedEvent;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

// Scheduling off (poll-interval huge) so the worker doesn't touch these rows: this class is only
// about what the listener writes.
@Testcontainers
@SpringBootTest(properties = "slotwise.notification.poll-interval=1h")
class NotificationListenerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

    @Autowired
    KafkaTemplate<String, ReservationCreatedEvent> kafkaTemplate;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void eventStored_asPending() {
        this.publish(1L, "owner-1");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(this.statusOf("reservation-created:1")).isEqualTo("PENDING");
            assertThat(this.jdbcClient.sql("SELECT attempts FROM notification WHERE event_id = 'reservation-created:1'")
                    .query(Integer.class).single()).isZero();
        });
    }

    @Test
    void sameEventTwice_oneRow() {
        this.publish(2L, "owner-2");
        this.publish(2L, "owner-2");
        this.publish(3L, "owner-2"); // marker: once stored, the duplicate (same key, earlier) was consumed too

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(this.statusOf("reservation-created:3")).isEqualTo("PENDING");
            assertThat(this.jdbcClient.sql("SELECT count(*) FROM notification WHERE event_id = 'reservation-created:2'")
                    .query(Long.class).single()).isEqualTo(1L);
        });
    }

    @Test
    void legacyEventWithoutOwner_isFailed() {
        this.publish(4L, null);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(this.statusOf("reservation-created:4")).isEqualTo("FAILED");
            assertThat(this.jdbcClient.sql("SELECT last_error FROM notification WHERE event_id = 'reservation-created:4'")
                    .query(String.class).single()).containsIgnoringCase("owner");
        });
    }

    private void publish(final Long reservationId, final String ownerSubject) {
        // Same key for every event in this class: one partition, so order is preserved.
        this.kafkaTemplate.send(ReservationCreatedEvent.TOPIC, "k",
                new ReservationCreatedEvent(reservationId, 10L, ownerSubject,
                        Instant.parse("2026-06-01T10:00:00Z"), Instant.parse("2026-06-01T11:00:00Z")));
    }

    private String statusOf(final String eventId) {
        return this.jdbcClient.sql("SELECT status FROM notification WHERE event_id = :id")
                .param("id", eventId).query(String.class).optional().orElse("");
    }
}
