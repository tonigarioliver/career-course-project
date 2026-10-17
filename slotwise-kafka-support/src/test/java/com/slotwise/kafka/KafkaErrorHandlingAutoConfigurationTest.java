package com.slotwise.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.slotwise.events.ReservationCreatedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

// No @Import of the auto-configuration: if the error handler is active, META-INF/.../AutoConfiguration.imports works.
@Testcontainers
@SpringBootTest(classes = KafkaErrorHandlingAutoConfigurationTest.TestApp.class, properties = {
        "slotwise.kafka.retry.max-attempts=2",
        "slotwise.kafka.retry.initial-interval=50ms",
        "spring.kafka.consumer.group-id=support-test",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JacksonJsonDeserializer",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=com.slotwise.events",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JacksonJsonSerializer"})
class KafkaErrorHandlingAutoConfigurationTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

    @Autowired
    KafkaTemplate<String, ReservationCreatedEvent> kafkaTemplate;

    @Autowired
    AlwaysFailingListener listener;

    @Test
    void failingRecord_isRetriedThenDeadLettered() {
        this.kafkaTemplate.send(ReservationCreatedEvent.TOPIC, "1", new ReservationCreatedEvent(
                1L, 10L, "owner", Instant.parse("2026-05-01T10:00:00Z"), Instant.parse("2026-05-01T11:00:00Z")));

        final var dead = this.readDeadLetters("1");

        // max-attempts=2 retries: the first delivery plus 2 retries.
        assertThat(this.listener.calls.get()).isEqualTo(3);
        assertThat(dead.getFirst().value()).contains("\"reservationId\":1");
    }

    private List<ConsumerRecord<String, String>> readDeadLetters(final String key) {
        final var props = this.kafkaTemplate.getProducerFactory().getConfigurationProperties();
        try (var consumer = new KafkaConsumer<>(
                Map.<String, Object>of("bootstrap.servers", props.get("bootstrap.servers"),
                        "group.id", "dlt-test-" + UUID.randomUUID(), "auto.offset.reset", "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(ReservationCreatedEvent.DLT_TOPIC));
            final var found = new ArrayList<ConsumerRecord<String, String>>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
                assertThat(found).isNotEmpty();
            });
            return found;
        }
    }

    @Configuration
    @EnableAutoConfiguration
    @Import(AlwaysFailingListener.class)
    static class TestApp {
    }

    @Component
    static class AlwaysFailingListener {
        final AtomicInteger calls = new AtomicInteger();

        @KafkaListener(topics = ReservationCreatedEvent.TOPIC)
        void onEvent(final ReservationCreatedEvent event) {
            this.calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }
    }
}
