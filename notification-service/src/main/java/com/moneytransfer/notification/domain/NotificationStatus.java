package com.moneytransfer.notification.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Notification lifecycle (rule N2):
 * <pre>
 *   PENDING -> SENT | FAILED
 *   FAILED  -> SENT | FAILED   (a retry may succeed or fail again)
 *   SENT    -> nothing         (final: a delivered message is never sent again)
 * </pre>
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED;

    public Set<NotificationStatus> allowedNext() {
        return switch (this) {
            case PENDING, FAILED -> EnumSet.of(SENT, FAILED);
            case SENT -> EnumSet.noneOf(NotificationStatus.class);
        };
    }

    public boolean canTransitionTo(NotificationStatus next) {
        return allowedNext().contains(next);
    }
}
