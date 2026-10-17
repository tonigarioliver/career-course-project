package com.slotwise.booking.config;

import com.slotwise.events.ReservationCreatedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

// Declares "reservation-events" explicitly instead of letting Kafka's auto.create.topics.enable
// default (single partition) create it on first publish — 3 partitions so there's something to
// actually demonstrate for Fase 4's "Topics, Partitions" item (multiple partitions to spread a
// consumer group across, not just one). Spring Boot auto-configures the KafkaAdmin bean that
// applies this on startup, reconciling with whatever the broker already has.
@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic reservationEventsTopic() {
        return TopicBuilder.name(ReservationCreatedEvent.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    // Same 3 partitions and same owner (booking-service) as the source topic, for the same reason:
    // consumers that dead-letter a record must not be the ones auto-creating it with 1 partition.
    @Bean
    NewTopic reservationEventsDeadLetterTopic() {
        return TopicBuilder.name(ReservationCreatedEvent.DLT_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
