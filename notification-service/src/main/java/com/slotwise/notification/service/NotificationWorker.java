package com.slotwise.notification.service;

import com.slotwise.notification.data.Notification;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Orchestrates three steps so that no DB transaction is open during the Keycloak call or the send:
//  1. claim   — NotificationWriteService.claimNextDue() (short tx, lease in next_attempt_at);
//  2. work    — here, outside any transaction;
//  3. close   — NotificationWriteService.markSent / markFailed / recordTransientFailure (short tx).
// Deliberately not @Transactional: one transaction around all three would hold a pooled connection and
// the row lock across the HTTP calls, and a failure would roll back the attempt counter and backoff.
// At-least-once: if the process dies in step 2 after the send, the lease expires and it is sent again.
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationWorker {

    private final NotificationWriteService notificationWriteService;
    private final KeycloakUserClient keycloakUserClient;
    private final NotificationSender notificationSender;

    // initialDelay = one interval: the first poll doesn't race whatever else is starting up (tests insert
    // rows right after the context is ready and drive processNext() themselves).
    @Scheduled(fixedDelayString = "${slotwise.notification.poll-interval:1s}",
            initialDelayString = "${slotwise.notification.poll-interval:1s}")
    public void poll() {
        while (this.processNext()) {
            // drains everything currently due
        }
    }

    // Returns false when nothing was due.
    public boolean processNext() {
        final Optional<Notification> claimed = this.notificationWriteService.claimNextDue();
        if (claimed.isEmpty()) {
            return false;
        }
        final Notification notification = claimed.get();
        final String email;
        try {
            email = this.keycloakUserClient.findEmail(notification.getOwnerSubject());
            this.notificationSender.send(email, notification);
        } catch (PermanentNotificationException e) {
            log.warn("Notification {} failed permanently: {}", notification.getEventId(), e.getMessage());
            this.notificationWriteService.markFailed(notification.getId(), e.getMessage());
            return true;
        } catch (RuntimeException e) {
            log.warn("Notification {} attempt {} failed: {}", notification.getEventId(), notification.getAttempts(), e.getMessage());
            this.notificationWriteService.recordTransientFailure(notification.getId(), String.valueOf(e.getMessage()));
            return true;
        }
        // Outside the try on purpose: the notification already went out, so a failure recording it is not a
        // send failure (it would be labelled FAILED on the last attempt although delivered). It propagates,
        // the lease expires and the row is retried — a duplicate send, which at-least-once allows.
        this.notificationWriteService.markSent(notification.getId(), email);
        return true;
    }
}
