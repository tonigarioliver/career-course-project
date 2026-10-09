package com.slotwise.booking.service;

import com.slotwise.booking.data.ReservationRepository;
import com.slotwise.booking.model.Caller;
import com.slotwise.booking.model.CreateReservationRequest;
import com.slotwise.booking.model.ReservationDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
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

    // The owner is the caller unless an admin names someone else. A client-supplied owner used to be taken as-is,
    // which let any user create reservations (and trigger notifications) in another user's name.
    public ReservationDto create(@NotNull Caller caller, @NotNull @Valid CreateReservationRequest request) {
        final String owner = resolveOwner(caller, request.ownerSubject());
        return this.reservationWriteService.create(request.toBuilder().ownerSubject(owner).build());
    }

    private static String resolveOwner(final Caller caller, final @Nullable String requested) {
        if (!StringUtils.hasText(requested) || requested.equals(caller.subject())) {
            return caller.subject();
        }
        if (!caller.admin()) {
            throw new ReservationOwnerNotAllowedException(caller.subject(), requested);
        }
        return requested;
    }

    @Transactional(readOnly = true)
    public Page<ReservationDto> listByResource(@NotNull Long resourceId, Pageable pageable) {
        return this.reservationRepository.findSummariesByResourceId(resourceId, pageable);
    }
}
