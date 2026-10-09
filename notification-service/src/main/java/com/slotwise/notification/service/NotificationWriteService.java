package com.slotwise.notification.service;

import com.slotwise.notification.config.NotificationProperties;
import com.slotwise.notification.data.Notification;
import com.slotwise.notification.data.NotificationRepository;
import com.slotwise.notification.data.NotificationStatus;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

// The short transactions of the inbox worker; NotificationWorker (the non-transactional orchestrator)
// calls these through Spring's proxy, with the Keycloak call and the send in between, outside any
// transaction. Same split as ReservationService / ReservationWriteService.
@Service
@Validated
@RequiredArgsConstructor
public class NotificationWriteService {

    private final NotificationRepository notificationRepository;
    private final NotificationProperties notificationProperties;

    // Locks one due row (FOR UPDATE SKIP LOCKED), counts the attempt and pushes next_attempt_at forward by
    // claimTimeout — the lease: a worker that crashes mid-work leaves a row that simply becomes due again.
    // A row that comes back with its attempts already used up (every attempt died without recording a
    // failure) is failed here instead of being claimed forever: maxAttempts is otherwise only checked in
    // recordTransientFailure, which a crashing worker never reaches.
    @Transactional
    public Optional<Notification> claimNextDue() {
        Optional<Notification> due = this.notificationRepository.lockNextDue();
        while (due.isPresent() && due.get().getAttempts() >= this.notificationProperties.maxAttempts()) {
            final Notification exhausted = due.get();
            exhausted.setStatus(NotificationStatus.FAILED);
            exhausted.setLastError("Lease expired after max attempts (worker died mid-work?)");
            this.notificationRepository.saveAndFlush(exhausted);
            due = this.notificationRepository.lockNextDue();
        }
        return due.map(n -> {
            n.setAttempts(n.getAttempts() + 1);
            n.setNextAttemptAt(Instant.now().plus(this.notificationProperties.claimTimeout()));
            return n;
        });
    }

    @Transactional
    public void markSent(@NotNull final Long id, @NotNull final String recipient) {
        this.notificationRepository.findById(id).ifPresent(n -> {
            n.setStatus(NotificationStatus.SENT);
            n.setRecipient(recipient);
            n.setSentAt(Instant.now());
        });
    }

    @Transactional
    public void markFailed(@NotNull final Long id, @NotNull final String error) {
        this.notificationRepository.findById(id).ifPresent(n -> {
            n.setStatus(NotificationStatus.FAILED);
            n.setLastError(error);
        });
    }

    // A failure that may go away: retry after backoff, unless the attempts are used up.
    @Transactional
    public void recordTransientFailure(@NotNull final Long id, @NotNull final String error) {
        this.notificationRepository.findById(id).ifPresent(n -> {
            n.setLastError(error);
            if (n.getAttempts() >= this.notificationProperties.maxAttempts()) {
                n.setStatus(NotificationStatus.FAILED);
            } else {
                n.setNextAttemptAt(Instant.now().plus(this.notificationProperties.backoff()));
            }
        });
    }
}
