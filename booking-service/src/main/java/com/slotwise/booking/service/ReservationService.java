package com.slotwise.booking.service;

import com.slotwise.booking.data.ReservationRepository;
import com.slotwise.booking.model.CreateReservationRequest;
import com.slotwise.booking.model.ReservationDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

// Orchestrator: the single entry point every caller (controller, tests, anything future) goes
// through to create a reservation, so "publish the reservation-created event" stays a
// guarantee of calling this method, not something each caller has to remember. create()
// delegates the transactional work to ReservationWriteService — a real bean, so the call goes
// through Spring's proxy — then publishes to Kafka afterward. By the time control returns
// here, ReservationWriteService's @Transactional has already committed (or thrown, in which
// case this line is never reached) — a normal method return from a proxied @Transactional
// bean already implies its commit happened, no @TransactionalEventListener/AFTER_COMMIT
// needed to get that ordering. See decisions.md for the Fase 3 Redis Pub/Sub version this
// replaced and why moving to Kafka (Fase 4) changes what's actually guaranteed.
@Service
@Validated
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationWriteService reservationWriteService;
    private final ReservationRepository reservationRepository;
    private final KafkaTemplate<String, ReservationCreatedEvent> kafkaTemplate;

    public ReservationDto create(@NotNull @Valid CreateReservationRequest request) {
        final ReservationDto saved = this.reservationWriteService.create(request);
        // Keyed by resourceId, not left null: Kafka only orders messages within a partition,
        // not across the whole topic, so events for the same resource need the same key to
        // land on the same partition and stay in creation order relative to each other.
        // Events for different resources can land on different partitions and be processed
        // out of order relative to one another — fine, nothing here depends on global order.
        this.kafkaTemplate.send(ReservationCreatedEvent.TOPIC, request.resourceId().toString(),
                new ReservationCreatedEvent(saved.id(), request.resourceId(), request.startTime(), request.endTime()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<ReservationDto> listByResource(@NotNull Long resourceId, Pageable pageable) {
        return this.reservationRepository.findSummariesByResourceId(resourceId, pageable);
    }
}
