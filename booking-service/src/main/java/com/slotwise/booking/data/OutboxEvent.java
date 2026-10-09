package com.slotwise.booking.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

// DDL in db/migration/V2__outbox_event.sql. publishedAt == null means "not yet sent to Kafka".
@Entity
@Table(name = "outbox_event")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private String messageKey;

    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    @Nullable
    private Instant publishedAt;

    public OutboxEvent(final String topic, final String messageKey, final String payload) {
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = Instant.now();
    }
}
