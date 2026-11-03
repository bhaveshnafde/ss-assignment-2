package com.moneytransfer.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Money set aside on the source account for one transfer. Keyed by transferId, so there can
 * only ever be one hold per transfer.
 */
@Entity
@Table(name = "holds")
public class Hold {

    @Id
    @Column(length = 64)
    private String transferId;

    @Column(nullable = false, length = 64)
    private String sourceAccountId;

    @Column(nullable = false, length = 64)
    private String destinationAccountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HoldStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Hold() {
        // for JPA
    }

    public static Hold place(String transferId, String sourceAccountId, String destinationAccountId,
                             BigDecimal amount, String currency) {
        Hold h = new Hold();
        h.transferId = Objects.requireNonNull(transferId);
        h.sourceAccountId = Objects.requireNonNull(sourceAccountId);
        h.destinationAccountId = Objects.requireNonNull(destinationAccountId);
        h.amount = Objects.requireNonNull(amount);
        h.currency = Objects.requireNonNull(currency);
        h.status = HoldStatus.HELD;
        h.createdAt = Instant.now();
        h.updatedAt = h.createdAt;
        return h;
    }

    public void markCaptured() {
        transitionTo(HoldStatus.CAPTURED);
    }

    public void markReleased() {
        transitionTo(HoldStatus.RELEASED);
    }

    /** True if a repeated "place hold" request describes this same hold. */
    public boolean sameRequest(String sourceAccountId, String destinationAccountId, BigDecimal amount, String currency) {
        return this.sourceAccountId.equals(sourceAccountId)
                && this.destinationAccountId.equals(destinationAccountId)
                && this.amount.compareTo(amount) == 0
                && this.currency.equalsIgnoreCase(currency);
    }

    private void transitionTo(HoldStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateTransitionException("Hold", transferId, status, next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public String getTransferId() { return transferId; }
    public String getSourceAccountId() { return sourceAccountId; }
    public String getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public HoldStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
