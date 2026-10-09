package com.slotwise.booking.service;

public class ReservationOwnerNotAllowedException extends RuntimeException {

    public ReservationOwnerNotAllowedException(final String callerSubject, final String requestedOwner) {
        super("User " + callerSubject + " cannot book on behalf of " + requestedOwner + " (admin only)");
    }
}
