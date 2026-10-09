package com.slotwise.notification.service;

import com.slotwise.events.ReservationCreatedEvent;
import com.slotwise.notification.data.NotificationRepository;
import com.slotwise.notification.data.NotificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

// Inbox side: only records the event (fast, no external calls), NotificationWorker does the rest.
// A duplicate delivery hits the UNIQUE event_id and is skipped.
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationListener {

    private final NotificationRepository notificationRepository;

    @Transactional
    @KafkaListener(topics = ReservationCreatedEvent.TOPIC)
    void onReservationCreated(final ReservationCreatedEvent event) {
        // Events published before ownerSubject existed carry none: nobody to look up, retrying can't help.
        final boolean legacy = !StringUtils.hasText(event.ownerSubject());
        final int inserted = this.notificationRepository.insertIfAbsent(
                "reservation-created:" + event.reservationId(), event.reservationId(), event.ownerSubject(),
                (legacy ? NotificationStatus.FAILED : NotificationStatus.PENDING).name(),
                legacy ? "Event has no ownerSubject (published before the field existed)" : null);
        if (inserted == 0) {
            log.info("Duplicate notification event skipped: {}", event);
        }
    }
}
