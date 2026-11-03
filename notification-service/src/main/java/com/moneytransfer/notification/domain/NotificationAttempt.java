package com.moneytransfer.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One call to the provider: the evidence trail for retries (insert-only). */
@Entity
@Table(name = "notification_attempts")
public class NotificationAttempt {

    public enum Outcome { SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long notificationId;

    @Column(nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Outcome outcome;

    @Column(length = 300)
    private String error;

    @Column(nullable = false)
    private long durationMs;

    @Column(nullable = false)
    private Instant attemptedAt;

    protected NotificationAttempt() {
        // for JPA
    }

    public NotificationAttempt(Long notificationId, int attemptNo, Outcome outcome, String error, long durationMs,
                               Instant attemptedAt) {
        this.notificationId = notificationId;
        this.attemptNo = attemptNo;
        this.outcome = outcome;
        this.error = error == null || error.length() <= 300 ? error : error.substring(0, 300);
        this.durationMs = durationMs;
        this.attemptedAt = attemptedAt;
    }

    public Long getNotificationId() { return notificationId; }
    public int getAttemptNo() { return attemptNo; }
    public Outcome getOutcome() { return outcome; }
    public String getError() { return error; }
    public long getDurationMs() { return durationMs; }
    public Instant getAttemptedAt() { return attemptedAt; }
}
