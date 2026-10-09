package com.slotwise.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// The worker is real; only KeycloakUserClient (the boundary with the outside world) is a mock.
// Scheduler and Kafka listener are off so each test drives processNext() itself. backoff and
// claimTimeout are an hour: any row the worker has touched is "not due" until the test says so.
@Testcontainers
@SpringBootTest(properties = {
        "slotwise.notification.poll-interval=1h",
        "slotwise.notification.max-attempts=3",
        "slotwise.notification.backoff=1h",
        "slotwise.notification.claim-timeout=1h",
        "spring.kafka.listener.auto-startup=false"})
class NotificationWorkerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    NotificationWorker worker;

    @Autowired
    JdbcClient jdbcClient;

    @MockitoBean
    KeycloakUserClient keycloakUserClient;

    @MockitoSpyBean
    NotificationWriteService notificationWriteService;

    @BeforeEach
    void seedOneDueRow() {
        this.jdbcClient.sql("DELETE FROM notification").update();
        this.jdbcClient.sql("""
                INSERT INTO notification (event_id, reservation_id, owner_subject, status, next_attempt_at, created_at)
                VALUES ('reservation-created:1', 1, 'owner-1', 'PENDING', now() - interval '1 minute', now())
                """).update();
    }

    private Map<String, Object> row() {
        return this.jdbcClient.sql("SELECT status, attempts, recipient, last_error, sent_at, "
                        + "next_attempt_at > now() AS delayed FROM notification").query().singleRow();
    }

    private void makeDueAgain() {
        this.jdbcClient.sql("UPDATE notification SET next_attempt_at = now() - interval '1 minute'").update();
    }

    @Test
    void success_marksSentWithRecipient() {
        when(this.keycloakUserClient.findEmail("owner-1")).thenReturn("owner@slotwise.local");

        assertThat(this.worker.processNext()).isTrue();

        assertThat(this.row()).containsEntry("status", "SENT").containsEntry("attempts", 1)
                .containsEntry("recipient", "owner@slotwise.local");
        assertThat(this.row().get("sent_at")).isNotNull();
        assertThat(this.worker.processNext()).isFalse(); // nothing left due
    }

    @Test
    void transientFailure_addsAttemptAndDelays_staysPending() {
        when(this.keycloakUserClient.findEmail(anyString())).thenThrow(new IllegalStateException("Keycloak down"));

        this.worker.processNext();

        assertThat(this.row()).containsEntry("status", "PENDING").containsEntry("attempts", 1)
                .containsEntry("delayed", true);
        assertThat(this.row().get("last_error").toString()).contains("Keycloak down");
        assertThat(this.worker.processNext()).isFalse(); // delayed by backoff, so not picked again
    }

    @Test
    void reachingMaxAttempts_marksFailed() {
        when(this.keycloakUserClient.findEmail(anyString())).thenThrow(new IllegalStateException("Keycloak down"));

        for (int i = 0; i < 3; i++) {
            this.makeDueAgain();
            assertThat(this.worker.processNext()).isTrue();
        }

        assertThat(this.row()).containsEntry("status", "FAILED").containsEntry("attempts", 3);
        this.makeDueAgain();
        assertThat(this.worker.processNext()).isFalse(); // FAILED rows are never retried
    }

    @Test
    void permanentFailure_failsImmediately() {
        when(this.keycloakUserClient.findEmail(anyString())).thenThrow(new PermanentNotificationException("no email"));

        this.worker.processNext();

        assertThat(this.row()).containsEntry("status", "FAILED").containsEntry("attempts", 1);
    }

    @Test
    void claimedRow_notReclaimedBeforeLeaseExpires() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        when(this.keycloakUserClient.findEmail(anyString())).thenAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return "owner@slotwise.local";
        });

        final Thread first = new Thread(this.worker::processNext);
        first.start();
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        // First worker is mid-Keycloak-call and holds the claim: a second worker finds nothing due.
        assertThat(this.worker.processNext()).isFalse();

        release.countDown();
        first.join(10_000);
        assertThat(this.row()).containsEntry("status", "SENT").containsEntry("attempts", 1);
    }

    // A worker that keeps dying mid-work never reaches recordTransientFailure, so maxAttempts must also
    // be enforced when a row is claimed again after its lease expired.
    @Test
    void expiredLeaseAfterMaxAttempts_isFailedNotRetried() {
        this.jdbcClient.sql("UPDATE notification SET attempts = 3").update();

        assertThat(this.worker.processNext()).isFalse();

        verifyNoInteractions(this.keycloakUserClient);
        assertThat(this.row()).containsEntry("status", "FAILED").containsEntry("attempts", 3);
        assertThat(this.row().get("last_error").toString()).contains("max attempts");
    }

    // The email already went out: a failure while recording that must not be classified as a send
    // failure (on the last attempt it would label a delivered notification FAILED).
    @Test
    void markSentFailsAfterSend_isNotRecordedAsSendFailure() {
        when(this.keycloakUserClient.findEmail(anyString())).thenReturn("owner@slotwise.local");
        this.jdbcClient.sql("UPDATE notification SET attempts = 2").update(); // claim makes it the last attempt (3)
        doThrow(new IllegalStateException("db down")).when(this.notificationWriteService).markSent(anyLong(), anyString());

        assertThatThrownBy(() -> this.worker.processNext()).hasMessageContaining("db down");

        assertThat(this.row()).containsEntry("status", "PENDING");
        assertThat(this.row().get("last_error")).isNull();
    }
}
