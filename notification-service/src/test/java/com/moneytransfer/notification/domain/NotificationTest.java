package com.moneytransfer.notification.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N2 (SENT is final) and N3 (every attempt is counted, failures included). */
class NotificationTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private static Notification newNotification() {
        return Notification.request("t-1", "TRANSFER_COMPLETED", "ACC-1", Channel.SMS, "You sent 100", NOW);
    }

    @Test
    void newNotificationIsPendingWithNoAttempts() {
        Notification n = newNotification();
        assertEquals(NotificationStatus.PENDING, n.getStatus());
        assertEquals(0, n.getAttempts());
        assertNull(n.getSentAt());
    }

    @Test
    void N3_failuresAreCountedAndARetryCanSucceed() {
        Notification n = newNotification();
        n.recordFailure("provider down", NOW);
        n.recordFailure("provider down", NOW);
        assertEquals(NotificationStatus.FAILED, n.getStatus());
        assertEquals(2, n.getAttempts());
        assertEquals("provider down", n.getLastError());

        n.recordSuccess("SMS-1234", NOW);
        assertEquals(NotificationStatus.SENT, n.getStatus());
        assertEquals(3, n.getAttempts());
        assertNull(n.getLastError());
        assertEquals(NOW, n.getSentAt());
    }

    @Test
    void N2_aSentNotificationCannotBeSentOrFailedAgain() {
        Notification n = newNotification();
        n.recordSuccess("SMS-1234", NOW);
        assertTrue(n.isSent());
        assertThrows(IllegalStateException.class, () -> n.recordSuccess("SMS-5678", NOW));
        assertThrows(IllegalStateException.class, () -> n.recordFailure("late error", NOW));
        assertEquals(1, n.getAttempts());
    }

    @Test
    void repeatedRequestIsRecognised() {
        Notification n = newNotification();
        assertTrue(n.sameRequest("ACC-1", Channel.SMS, "You sent 100"));
        assertFalse(n.sameRequest("ACC-1", Channel.EMAIL, "You sent 100"));
        assertFalse(n.sameRequest("ACC-1", Channel.SMS, "You sent 999"));
        assertFalse(n.sameRequest("ACC-2", Channel.SMS, "You sent 100"));
    }

    @Test
    void longProviderErrorsAreTrimmed() {
        Notification n = newNotification();
        n.recordFailure("x".repeat(1000), NOW);
        assertEquals(300, n.getLastError().length());
    }
}
