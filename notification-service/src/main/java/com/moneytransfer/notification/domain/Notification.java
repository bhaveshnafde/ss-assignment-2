package com.moneytransfer.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/**
 * One message to one customer about one transfer. UNIQUE (transfer_id, template) in the database
 * means there is only ever one per transfer, however often it is requested (N1).
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String transferId;

    @Column(nullable = false, length = 40)
    private String template;

    @Column(nullable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Channel channel;

    @Column(nullable = false, length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 300)
    private String lastError;

    @Column(length = 40)
    private String providerMessageId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Notification() {
        // for JPA
    }

    public static Notification request(String transferId, String template, String accountId, Channel channel,
                                       String message, Instant now) {
        Notification n = new Notification();
        n.transferId = Objects.requireNonNull(transferId);
        n.template = Objects.requireNonNull(template);
        n.accountId = Objects.requireNonNull(accountId);
        n.channel = Objects.requireNonNull(channel);
        n.message = Objects.requireNonNull(message);
        n.status = NotificationStatus.PENDING;
        n.attempts = 0;
        n.createdAt = Objects.requireNonNull(now);
        n.updatedAt = now;
        return n;
    }

    /** The provider delivered it. Final (N2). */
    public void recordSuccess(String providerMessageId, Instant now) {
        transitionTo(NotificationStatus.SENT, now);
        this.attempts++;
        this.providerMessageId = providerMessageId;
        this.sentAt = now;
        this.lastError = null;
    }

    /** The provider failed. The attempt is still counted (N3); a later retry may succeed. */
    public void recordFailure(String error, Instant now) {
        transitionTo(NotificationStatus.FAILED, now);
        this.attempts++;
        this.lastError = error == null || error.length() <= 300 ? error : error.substring(0, 300);
    }

    public boolean isSent() {
        return status == NotificationStatus.SENT;
    }

    /** True if a repeated request describes this same notification. */
    public boolean sameRequest(String accountId, Channel channel, String message) {
        return this.accountId.equals(accountId) && this.channel == channel && this.message.equals(message);
    }

    private void transitionTo(NotificationStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("notification " + notificationId() + " cannot move from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    /** Readable id used in the API, e.g. N-000045. */
    public String notificationId() {
        return id == null ? null : "N-%06d".formatted(id);
    }

    public Long getId() { return id; }
    public String getTransferId() { return transferId; }
    public String getTemplate() { return template; }
    public String getAccountId() { return accountId; }
    public Channel getChannel() { return channel; }
    public String getMessage() { return message; }
    public NotificationStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public String getProviderMessageId() { return providerMessageId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
