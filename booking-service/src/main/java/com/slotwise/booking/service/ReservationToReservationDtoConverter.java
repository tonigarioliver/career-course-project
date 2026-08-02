package com.slotwise.booking.service;

import com.slotwise.booking.data.Reservation;
import com.slotwise.booking.model.ReservationDto;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class ReservationToReservationDtoConverter implements Converter<Reservation, ReservationDto> {

    @Override
    public ReservationDto convert(Reservation source) {
        return ReservationDto.builder()
                .id(source.getId())
                .resourceId(source.getResource().getId())
                .startTime(source.getStartTime())
                .endTime(source.getEndTime())
                .ownerSubject(source.getOwnerSubject())
                .status(source.getStatus())
                .build();
    }
}
