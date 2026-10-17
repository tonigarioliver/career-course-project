package com.slotwise.booking.config;

import com.slotwise.events.ReservationCreatedEvent;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

// Without this bean Spring Kafka retries a failing record 9 times back to back and then drops it.
// Now: 4 retries with exponential backoff, then the record goes to ReservationCreatedEvent.DLT_TOPIC
// (headers say where it came from and why it failed) and the consumer moves on.
// Boot registers a CommonErrorHandler bean on the listener container factory by itself.
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(final DefaultKafkaProducerFactory<?, ?> bootProducerFactory) {
        // Config of Boot's own producer factory, not KafkaProperties: only the factory carries the broker
        // address a @ServiceConnection (Testcontainers) or other connection-details bean supplies.
        // A record that failed *deserialization* reaches the recoverer as raw byte[], not as the
        // event, so the DLT producer must serialize both.
        final var producerFactory = new DefaultKafkaProducerFactory<String, Object>(
                bootProducerFactory.getConfigurationProperties(), new StringSerializer(),
                new DelegatingByTypeSerializer(Map.of(
                        byte[].class, new ByteArraySerializer(),
                        ReservationCreatedEvent.class, new JacksonJsonSerializer<>())));
        // Partition -1: let the producer choose. Keeping the source partition would fail when the broker
        // auto-created the DLT with fewer partitions than the source topic.
        final var recoverer = new DeadLetterPublishingRecoverer(new KafkaTemplate<>(producerFactory),
                (record, exception) -> new TopicPartition(ReservationCreatedEvent.DLT_TOPIC, -1));
        final var backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxAttempts(4);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
