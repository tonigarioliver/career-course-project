package com.slotwise.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.slotwise.events.ReservationCreatedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuditEventListenerIntegrationTest {

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
    void publishedEvent_isStored() {
        this.publish(1L, 10L);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(this.resourceIdOf("reservation-created:1")).isEqualTo(10L));
    }

    @Test
    void duplicateEvent_storedOnce() {
        this.publish(2L, 20L);
        this.publish(2L, 20L);
        // Marker with a different id: once it is stored, the duplicate (published before it, same
        // partition key order isn't guaranteed across keys, so await both) has been consumed too.
        this.publish(3L, 20L);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(this.count("reservation-created:3")).isEqualTo(1L);
            assertThat(this.count("reservation-created:2")).isEqualTo(1L);
        });
    }

    @Test
    void poisonMessage_doesNotBlockLaterEvents() throws Exception {
        final var props = this.kafkaTemplate.getProducerFactory().getConfigurationProperties();
        final var rawFactory = new DefaultKafkaProducerFactory<String, byte[]>(
                Map.of("bootstrap.servers", props.get("bootstrap.servers")),
                new StringSerializer(), new ByteArraySerializer());
        try {
            // .get(): the poison must be on the topic *before* the valid event is sent, otherwise
            // the two producers can race and the valid event lands first, proving nothing.
            new KafkaTemplate<>(rawFactory).send(ReservationCreatedEvent.TOPIC, "30", "not-json".getBytes()).get();
        } finally {
            rawFactory.destroy();
        }

        this.publish(4L, 30L);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(this.count("reservation-created:4")).isEqualTo(1L));
    }

    private void publish(final Long reservationId, final Long resourceId) {
        this.kafkaTemplate.send(ReservationCreatedEvent.TOPIC, resourceId.toString(),
                new ReservationCreatedEvent(reservationId, resourceId, "owner-" + resourceId,
                        Instant.parse("2026-05-01T10:00:00Z"), Instant.parse("2026-05-01T11:00:00Z")));
    }

    private long count(final String eventId) {
        return this.jdbcClient.sql("SELECT count(*) FROM audit_event WHERE event_id = :id")
                .param("id", eventId).query(Long.class).single();
    }

    private Long resourceIdOf(final String eventId) {
        return this.jdbcClient.sql("SELECT resource_id FROM audit_event WHERE event_id = :id")
                .param("id", eventId).query(Long.class).optional().orElse(-1L);
    }
}
