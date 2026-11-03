package com.moneytransfer.notification.application;

import com.moneytransfer.notification.domain.Channel;
import com.moneytransfer.notification.domain.Notification;
import com.moneytransfer.notification.domain.NotificationAttempt;
import com.moneytransfer.notification.domain.NotificationRules;
import com.moneytransfer.notification.domain.NotificationStatus;
import com.moneytransfer.notification.domain.RuleViolationException;
import com.moneytransfer.notification.persistence.NotificationAttemptJpaRepository;
import com.moneytransfer.notification.persistence.NotificationJpaRepository;
import com.moneytransfer.notification.web.error.ApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Makes "notify" safe to repeat. The Transfer service retries when the provider fails or is slow,
 * so the same transfer can be notified twice, even at the same moment. It is delivered once.
 */
@Service
public class NotificationService {

    /** A notification with its attempt history, for the read endpoints. */
    public record View(Notification notification, List<NotificationAttempt> history) { }

    private final NotificationJpaRepository notifications;
    private final NotificationAttemptJpaRepository attempts;
    private final DeliveryTransactions delivery;

    public NotificationService(NotificationJpaRepository notifications, NotificationAttemptJpaRepository attempts,
                               DeliveryTransactions delivery) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.delivery = delivery;
    }

    /**
     * @return the notification, SENT
     * @throws ApiException 503 PROVIDER_DOWN when the provider failed this time (a retry may succeed)
     */
    public Notification notify(String transferId, String accountId, Channel channel, String template, String message) {
        NotificationRules.check(template, message);
        Notification n = findOrCreate(transferId, template, accountId, channel, message);
        if (!n.sameRequest(accountId, channel, message)) {
            throw new RuleViolationException(422, "NOTIFICATION_MISMATCH",
                    "transfer " + transferId + " already has a " + template + " notification with different details");
        }

        DeliveryTransactions.Attempt attempt = delivery.deliver(n.getId());
        Notification after = attempt.notification();
        if (after.getStatus() == NotificationStatus.FAILED) {
            throw new ApiException(503, "PROVIDER_DOWN",
                    "the notification provider failed (attempt " + after.getAttempts() + "): " + after.getLastError(),
                    List.of(Map.of("notificationId", after.notificationId(), "attempts", after.getAttempts())));
        }
        return after;
    }

    public List<View> forTransfer(String transferId) {
        return withHistory(notifications.findByTransferIdOrderByIdAsc(transferId));
    }

    public List<View> forAccount(String accountId, int limit) {
        return withHistory(notifications.findByAccountIdOrderByIdDesc(accountId, PageRequest.of(0, Math.max(1, Math.min(limit, 200)))));
    }

    /**
     * The notification row is created on its own, outside the delivery transaction. If another request
     * for the same transfer created it a moment earlier, the UNIQUE (transfer_id, template) constraint
     * refuses this insert and we use that row instead.
     */
    private Notification findOrCreate(String transferId, String template, String accountId, Channel channel, String message) {
        Optional<Notification> existing = notifications.findByTransferIdAndTemplate(transferId, template);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return notifications.saveAndFlush(
                    Notification.request(transferId, template, accountId, channel, message, Instant.now()));
        } catch (DataAccessException e) {
            for (int attempt = 0; attempt < 20; attempt++) {
                Optional<Notification> winner = notifications.findByTransferIdAndTemplate(transferId, template);
                if (winner.isPresent()) {
                    return winner.get();
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw e;
        }
    }

    private List<View> withHistory(List<Notification> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, List<NotificationAttempt>> byNotification = attempts
                .findByNotificationIdInOrderByNotificationIdAscAttemptNoAsc(found.stream().map(Notification::getId).toList())
                .stream().collect(Collectors.groupingBy(NotificationAttempt::getNotificationId));
        return found.stream().map(n -> new View(n, byNotification.getOrDefault(n.getId(), List.of()))).toList();
    }
}
