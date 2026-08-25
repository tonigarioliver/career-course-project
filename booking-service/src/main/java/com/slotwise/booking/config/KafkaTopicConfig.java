package com.slotwise.booking.config;

import com.slotwise.booking.service.ReservationCreatedEvent;
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
}
