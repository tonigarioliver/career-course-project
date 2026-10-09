package com.slotwise.booking.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Drives the outbox: ships unpublished rows to Kafka, one transaction per row (each
// OutboxPublishService.publishNext() call is its own transaction). At-least-once: a row is only
// marked published after the broker acked it, so if the commit fails after a successful send that
// one row is re-sent next tick — consumers must tolerate duplicates. The first failure aborts the
// tick (keeps per-resource order: a later event never overtakes a failed earlier one).
// Deliberately not @Transactional: the transactions live in OutboxPublishService.
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxPublishService outboxPublishService;

    @Scheduled(fixedDelay = 1000)
    public void publishPending() {
        while (this.outboxPublishService.publishNext()) {
            // drains until the outbox is empty
        }
    }
}
