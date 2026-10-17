package com.slotwise.booking.service;

import com.slotwise.booking.data.OutboxEvent;
import com.slotwise.booking.data.OutboxEventRepository;
import com.slotwise.events.ReservationCreatedEvent;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.json.JsonMapper;

// The transactional half of the outbox relay: one call = one transaction = one row. OutboxRelay
// (the non-transactional orchestrator) calls this bean through Spring's proxy, once per row, so each
// row commits on its own — same split as ReservationService / ReservationWriteService.
@Service
@Validated
@RequiredArgsConstructor
public class OutboxPublishService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, ReservationCreatedEvent> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final OutboxTraceContext outboxTraceContext;

    // Locks the next unpublished row (FOR UPDATE SKIP LOCKED), sends it, waits for the broker ack and
    // marks it published. Returns false when nothing was left. The lock must be held across the send,
    // which is why the send happens inside the transaction.
    @Transactional
    public boolean publishNext() {
        return this.outboxEventRepository.lockNextUnpublished().map(this::publish).isPresent();
    }

    // Separate method because send().get() throws checked exceptions, which a lambda can't.
    private OutboxEvent publish(final OutboxEvent outboxEvent) {
        final ReservationCreatedEvent event =
                this.jsonMapper.readValue(outboxEvent.getPayload(), ReservationCreatedEvent.class);
        // The send's own span (KafkaTemplate observation) becomes a child of the original request's trace.
        try (var ignored = this.outboxTraceContext.continueTrace(outboxEvent.getTraceContext())) {
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
