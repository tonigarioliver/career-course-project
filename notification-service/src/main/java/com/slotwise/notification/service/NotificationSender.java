package com.slotwise.notification.service;

import com.slotwise.notification.data.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Simulated delivery: just logs. ponytail: no interface until there is a second provider (SMTP, SMS).
@Slf4j
@Component
public class NotificationSender {

    public void send(final String recipient, final Notification notification) {
        log.info("Notification sent to {}: reservation {} confirmed", recipient, notification.getReservationId());
    }
}
