package com.slotwise.booking.service;

import com.slotwise.booking.data.ReservationRepository;
import com.slotwise.booking.model.CreateReservationRequest;
import com.slotwise.booking.model.ReservationDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

// Orchestrator: the single entry point every caller (controller, tests, anything future) goes
// through to create a reservation, so "publish the Pub/Sub event" stays a guarantee of calling
// this method, not something each caller has to remember. create() delegates the transactional
// work to ReservationWriteService — a real bean, so the call goes through Spring's proxy —
// then publishes straight to Redis afterward. By the time control returns here,
// ReservationWriteService's @Transactional has already committed (or thrown, in which case
// this line is never reached); no @TransactionalEventListener/AFTER_COMMIT needed to get that
// ordering, since a normal method return from a proxied @Transactional bean already implies
// its commit happened. See decisions.md for the earlier event-bus version this replaced and why.
@Service
@Validated
@RequiredArgsConstructor
public class ReservationService {

    static final String RESERVATION_EVENTS_CHANNEL = "reservation-events";

    private final ReservationWriteService reservationWriteService;
    private final ReservationRepository reservationRepository;
    private final RedissonClient redissonClient;

    public ReservationDto create(@NotNull @Valid CreateReservationRequest request) {
        final ReservationDto saved = this.reservationWriteService.create(request);
        // Fase 3 "Pub/Sub": fire-and-forget, no persistence/replay — see decisions.md for the
        // contrast with Fase 4's Kafka. No subscriber of this app's own; a same-process
        // listener would just be talking to itself, so ReservationServiceIntegrationTest's
        // pub/sub test plays the external-consumer role.
        this.redissonClient.getTopic(RESERVATION_EVENTS_CHANNEL)
                .publish(new ReservationCreatedEvent(saved.id(), request.resourceId(), request.startTime(), request.endTime()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<ReservationDto> listByResource(@NotNull Long resourceId, Pageable pageable) {
        return this.reservationRepository.findSummariesByResourceId(resourceId, pageable);
    }
}
