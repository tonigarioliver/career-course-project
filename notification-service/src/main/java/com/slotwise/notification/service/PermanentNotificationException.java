package com.slotwise.notification.service;

// Retrying can't fix this (user doesn't exist, has no email, ...): the notification goes straight
// to FAILED. Anything else thrown while processing is treated as transient and retried.
public class PermanentNotificationException extends RuntimeException {

    public PermanentNotificationException(final String message) {
        super(message);
    }
}
