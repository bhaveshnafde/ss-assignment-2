package com.moneytransfer.notification.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N2: the allowed status changes. */
class NotificationStatusTest {

    @Test
    void N2_pendingAndFailedCanBecomeSentOrFailed() {
        for (NotificationStatus from : new NotificationStatus[]{NotificationStatus.PENDING, NotificationStatus.FAILED}) {
            assertTrue(from.canTransitionTo(NotificationStatus.SENT));
            assertTrue(from.canTransitionTo(NotificationStatus.FAILED));
            assertFalse(from.canTransitionTo(NotificationStatus.PENDING));
        }
    }

    @Test
    void N2_sentIsFinal() {
        for (NotificationStatus next : NotificationStatus.values()) {
            assertFalse(NotificationStatus.SENT.canTransitionTo(next));
        }
    }
}
