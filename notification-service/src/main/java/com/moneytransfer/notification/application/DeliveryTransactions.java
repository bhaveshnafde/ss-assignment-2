package com.moneytransfer.notification.application;

import com.moneytransfer.notification.domain.Notification;
import com.moneytransfer.notification.domain.NotificationAttempt;
import com.moneytransfer.notification.persistence.NotificationAttemptJpaRepository;
import com.moneytransfer.notification.persistence.NotificationJpaRepository;
import com.moneytransfer.notification.provider.SimulatedProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * One delivery attempt in ONE database transaction, holding the notification's row lock throughout.
 * Called by {@link NotificationService}, which handles creating the notification and repeats.
 */
@Component
public class DeliveryTransactions {

    /** The notification after this request, and whether this request called the provider. */
    public record Attempt(Notification notification, boolean calledProvider) { }

    private static final Logger log = LoggerFactory.getLogger(DeliveryTransactions.class);

    private final NotificationJpaRepository notifications;
    private final NotificationAttemptJpaRepository attempts;
    private final SimulatedProvider provider;

    public DeliveryTransactions(NotificationJpaRepository notifications, NotificationAttemptJpaRepository attempts,
                                SimulatedProvider provider) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.provider = provider;
    }

    /**
     * 1. Lock the notification row (SELECT ... FOR UPDATE).
     * 2. Already SENT? Then this is a repeat: do nothing (N1).
     * 3. Otherwise call the provider and record the outcome, success or failure, with an attempt row (N3).
     * The outcome is saved even when the provider failed: a failure is a normal result here, not an
     * exception, so nothing is rolled back. The caller turns a failure into a 503.
     */
    @Transactional
    public Attempt deliver(Long notificationId) {
        Notification n = notifications.findForUpdate(notificationId)
                .orElseThrow(() -> new IllegalStateException("notification " + notificationId + " disappeared"));
        if (n.isSent()) {
            log.info("Notification {} for transfer {} already sent; nothing sent again",
                    n.notificationId(), n.getTransferId());
            return new Attempt(n, false);
        }

        long start = System.nanoTime();
        SimulatedProvider.Result result = provider.send(n.getChannel(), n.getAccountId(), n.getMessage());
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        Instant now = Instant.now();

        if (result.delivered()) {
            n.recordSuccess(result.providerMessageId(), now);
            log.info("Notification {} for transfer {} SENT on attempt {}", n.notificationId(), n.getTransferId(), n.getAttempts());
        } else {
            n.recordFailure(result.error(), now);
            log.warn("Notification {} for transfer {} FAILED on attempt {}: {}",
                    n.notificationId(), n.getTransferId(), n.getAttempts(), result.error());
        }
        attempts.save(new NotificationAttempt(n.getId(), n.getAttempts(),
                result.delivered() ? NotificationAttempt.Outcome.SENT : NotificationAttempt.Outcome.FAILED,
                result.error(), durationMs, now));
        return new Attempt(n, true);
    }
}
