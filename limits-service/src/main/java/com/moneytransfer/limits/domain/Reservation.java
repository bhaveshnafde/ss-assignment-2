package com.moneytransfer.limits.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/**
 * The share of an account's daily limit set aside for one transfer. Keyed by transferId, so there
 * can only ever be one per transfer. Remembers its day, so a release gives the amount back to the
 * day it was taken from even after midnight.
 */
@Entity
@Table(name = "limit_reservations")
public class Reservation {

    @Id
    @Column(length = 64)
    private String transferId;

    @Column(nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private LocalDate usageDay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant releasedAt;

    @Version
    private Long version;

    protected Reservation() {
        // for JPA
    }

    public static Reservation reserve(String transferId, String accountId, BigDecimal amount, String currency,
                                      LocalDate usageDay, Instant now) {
        Reservation r = new Reservation();
        r.transferId = Objects.requireNonNull(transferId);
        r.accountId = Objects.requireNonNull(accountId);
        r.amount = Objects.requireNonNull(amount);
        r.currency = currency.toUpperCase(Locale.ROOT);
        r.usageDay = Objects.requireNonNull(usageDay);
        r.status = ReservationStatus.RESERVED;
        r.createdAt = Objects.requireNonNull(now);
        return r;
    }

    /** LM1: release exactly once. */
    public void release(Instant now) {
        if (!status.canTransitionTo(ReservationStatus.RELEASED)) {
            throw new IllegalStateException("reservation for transfer " + transferId + " is already " + status);
        }
        this.status = ReservationStatus.RELEASED;
        this.releasedAt = now;
    }

    /** True if a repeated "reserve" request describes this same reservation. */
    public boolean sameRequest(String accountId, BigDecimal amount, String currency) {
        return this.accountId.equals(accountId)
                && this.amount.compareTo(amount) == 0
                && this.currency.equalsIgnoreCase(currency);
    }

    public String getTransferId() { return transferId; }
    public String getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public LocalDate getUsageDay() { return usageDay; }
    public ReservationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReleasedAt() { return releasedAt; }
}
