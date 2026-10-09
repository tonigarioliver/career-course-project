package com.slotwise.booking.service;

import com.slotwise.events.ReservationCreatedEvent;
import com.slotwise.booking.data.OutboxEvent;
import com.slotwise.booking.data.OutboxEventRepository;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

// Ships outbox rows to Kafka, one transaction per row. At-least-once: a row is only marked
// published after the broker acked it, so if the commit fails after a successful send, that one
// row is re-sent next tick — consumers must tolerate duplicates. The first failure aborts the
// tick (keeps per-resource order: a later event never overtakes a failed earlier one).
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, ReservationCreatedEvent> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 1000)
    public void publishPending() {
        // Not @Transactional: each publishNext() gets its own transaction via the template.
        while (this.publishNext()) {
            // drains until the outbox is empty
        }
    }

    // Returns false when nothing was left to publish.
    private boolean publishNext() {
        return Boolean.TRUE.equals(this.transactionTemplate.execute(status ->
                this.outboxEventRepository.lockNextUnpublished().map(this::publish).isPresent()));
    }

    // Separate method because send().get() throws checked exceptions, which a lambda can't.
    private OutboxEvent publish(final OutboxEvent outboxEvent) {
        final ReservationCreatedEvent event =
                this.jsonMapper.readValue(outboxEvent.getPayload(), ReservationCreatedEvent.class);
        try {
            this.kafkaTemplate.send(outboxEvent.getTopic(), outboxEvent.getMessageKey(), event).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing outbox event " + outboxEvent.getId(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Kafka rejected outbox event " + outboxEvent.getId(), e);
        }
        outboxEvent.setPublishedAt(Instant.now());
        return outboxEvent;
    }
}
