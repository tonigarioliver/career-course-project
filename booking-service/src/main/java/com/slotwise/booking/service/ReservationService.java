package com.slotwise.booking.service;

import com.slotwise.booking.data.Reservation;
import com.slotwise.booking.data.ReservationRepository;
import com.slotwise.booking.data.ReservationStatus;
import com.slotwise.booking.data.Resource;
import com.slotwise.booking.data.ResourceRepository;
import com.slotwise.booking.model.CreateReservationRequest;
import com.slotwise.booking.model.ReservationDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ResourceRepository resourceRepository;
    private final ConversionService conversionService;

    @Transactional
    public ReservationDto create(@NotNull @Valid CreateReservationRequest request) {
        if (!request.hasValidTimeRange()) {
            throw new IllegalArgumentException("startTime must be before endTime");
        }

        final Resource resource = this.resourceRepository.findById(request.resourceId())
                .orElseThrow(() -> new ResourceNotFoundException(request.resourceId()));

        final boolean hasOverlap = !this.reservationRepository
                .findOverlapping(request.resourceId(), request.startTime(), request.endTime())
                .isEmpty();
        if (hasOverlap) {
            throw new ReservationConflictException(request.resourceId());
        }

        final Reservation reservation = new Reservation();
        reservation.setResource(resource);
        reservation.setStartTime(request.startTime());
        reservation.setEndTime(request.endTime());
        reservation.setOwnerSubject(request.ownerSubject());
        reservation.setStatus(ReservationStatus.CONFIRMED);

        return this.conversionService.convert(this.reservationRepository.save(reservation), ReservationDto.class);
    }

    @Transactional(readOnly = true)
    public Page<ReservationDto> listByResource(@NotNull Long resourceId, Pageable pageable) {
        return this.reservationRepository.findSummariesByResourceId(resourceId, pageable);
    }
}
