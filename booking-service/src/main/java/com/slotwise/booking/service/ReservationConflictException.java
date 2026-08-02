package com.slotwise.booking.service;

public class ReservationConflictException extends RuntimeException {

    public ReservationConflictException(Long resourceId) {
        super("Resource " + resourceId + " already has an overlapping reservation for that time range");
    }
}
