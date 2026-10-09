package com.slotwise.booking.service;

import com.slotwise.booking.data.ReservationRepository;
import com.slotwise.booking.model.CreateReservationRequest;
import com.slotwise.booking.model.ReservationDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

// Entry point for reservation creation. The reservation-created event is no longer published
// here: ReservationWriteService stores it in the outbox in the same transaction and OutboxRelay
// sends it to Kafka, so the event can't be lost between commit and publish.
@Service
@Validated
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationWriteService reservationWriteService;
    private final ReservationRepository reservationRepository;

    public ReservationDto create(@NotNull @Valid CreateReservationRequest request) {
        return this.reservationWriteService.create(request);
    }

    @Transactional(readOnly = true)
    public Page<ReservationDto> listByResource(@NotNull Long resourceId, Pageable pageable) {
        return this.reservationRepository.findSummariesByResourceId(resourceId, pageable);
    }
}
