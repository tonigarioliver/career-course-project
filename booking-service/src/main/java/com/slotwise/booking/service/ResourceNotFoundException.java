package com.slotwise.booking.service;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(Long id) {
        super("Resource not found: " + id);
    }
}
