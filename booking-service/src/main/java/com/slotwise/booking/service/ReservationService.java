package com.slotwise.booking.service;

import com.slotwise.booking.config.ReservationProperties;
import com.slotwise.booking.data.Reservation;
import com.slotwise.booking.data.ReservationRepository;
import com.slotwise.booking.data.ReservationStatus;
import com.slotwise.booking.data.Resource;
import com.slotwise.booking.data.ResourceRepository;
import com.slotwise.booking.model.CreateReservationRequest;
import com.slotwise.booking.model.ReservationDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.ConversionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Slf4j
@Service
@Validated
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ResourceRepository resourceRepository;
    private final ConversionService conversionService;
    private final ReservationProperties reservationProperties;

    @Transactional
    public ReservationDto create(@NotNull @Valid CreateReservationRequest request) {
        final long durationMinutes = request.durationMinutes();
        if (durationMinutes < this.reservationProperties.minDurationMinutes()
                || durationMinutes > this.reservationProperties.maxDurationMinutes()) {
            throw new IllegalArgumentException("duration must be between "
                    + this.reservationProperties.minDurationMinutes() + " and "
                    + this.reservationProperties.maxDurationMinutes() + " minutes");
        }

        // Locks the resource row (SELECT ... FOR UPDATE) so a concurrent create() for the same
        // resource blocks here instead of racing the overlap check below — without it, two
        // requests can both see "no overlap" and both insert (reproduced and documented in
        // decisions.md). The lock is released on commit/rollback of this transaction.
        final Resource resource = this.resourceRepository.findByIdForUpdate(request.resourceId())
                .orElseThrow(() -> new ResourceNotFoundException(request.resourceId()));

        final boolean hasOverlap = !this.reservationRepository
                .findOverlapping(request.resourceId(), request.startTime(), request.endTime())
                .isEmpty();
        if (hasOverlap) {
            log.warn("Reservation conflict for resource {}: {} - {} overlaps an existing reservation",
                    request.resourceId(), request.startTime(), request.endTime());
            throw new ReservationConflictException(request.resourceId());
        }

        final Reservation reservation = new Reservation();
        reservation.setResource(resource);
        reservation.setStartTime(request.startTime());
        reservation.setEndTime(request.endTime());
        reservation.setOwnerSubject(request.ownerSubject());
        reservation.setStatus(ReservationStatus.CONFIRMED);

        final ReservationDto saved = this.saveAndTranslateConflict(reservation, request.resourceId());
        log.info("Created reservation {} for resource {} ({} - {})",
                saved.id(), request.resourceId(), request.startTime(), request.endTime());
        return saved;
    }

    // The FOR UPDATE lock above + the overlap check make this unreachable in practice — this
    // is the DB-level backstop (the reservations_no_overlap EXCLUDE constraint, see
    // decisions.md) catching whatever the application-level check didn't, e.g. a bug in the
    // check itself or a row inserted by something that bypasses this service entirely.
    private ReservationDto saveAndTranslateConflict(Reservation reservation, Long resourceId) {
        try {
            // See ResourceService for why: ConversionService.convert() is @Nullable per
            // Spring's contract, this package is @NullMarked.
            return Objects.requireNonNull(
                    this.conversionService.convert(this.reservationRepository.save(reservation), ReservationDto.class));
        } catch (DataIntegrityViolationException e) {
            throw new ReservationConflictException(resourceId);
        }
    }

    @Transactional(readOnly = true)
    public Page<ReservationDto> listByResource(@NotNull Long resourceId, Pageable pageable) {
        return this.reservationRepository.findSummariesByResourceId(resourceId, pageable);
    }
}
