package com.slotwise.booking.controller;

import com.slotwise.booking.model.CreateReservationRequest;
import com.slotwise.booking.model.ReservationDto;
import com.slotwise.booking.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping("/api/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationDto create(@Valid @RequestBody CreateReservationRequest request) {
        return reservationService.create(request);
    }

    @GetMapping("/api/resources/{resourceId}/reservations")
    public Page<ReservationDto> listByResource(
            @PathVariable Long resourceId,
            @PageableDefault(size = 20, sort = "startTime") Pageable pageable) {
        return reservationService.listByResource(resourceId, pageable);
    }
}
