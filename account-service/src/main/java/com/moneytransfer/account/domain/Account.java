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
 * An account with two amounts:
 * <ul>
 *   <li>balance: money really in the account</li>
 *   <li>available: balance minus money on hold, i.e. what can still be spent</li>
 * </ul>
 * Money never changes through setters on this class. It changes only through the atomic SQL
 * updates in AccountJpaRepository, so two requests can never both spend the same rupee (BR2).
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 100)
    private String holderName;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal available;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Account() {
        // for JPA
    }

    public static Account open(String id, String holderName, String currency, BigDecimal initialBalance) {
        if (initialBalance.signum() < 0) {
            throw new RuleViolationException(422, "NEGATIVE_INITIAL_BALANCE", "initialBalance must not be negative");
        }
        Account a = new Account();
        a.id = Objects.requireNonNull(id);
        a.holderName = Objects.requireNonNull(holderName);
        a.currency = Objects.requireNonNull(currency);
        a.balance = initialBalance;
        a.available = initialBalance;
        a.status = AccountStatus.ACTIVE;
        a.createdAt = Instant.now();
        a.updatedAt = a.createdAt;
        return a;
    }

    /**
     * Rule A2: an account can only be CLOSED when it holds no money and no money is on hold,
     * otherwise money would be stranded.
     */
    public void changeStatus(AccountStatus next) {
        if (status == next) {
            return;
        }
        if (!status.canTransitionTo(next)) {
            throw new RuleViolationException(409, "INVALID_STATUS_CHANGE",
                    "Account " + id + " cannot move from " + status + " to " + next);
        }
        if (next == AccountStatus.CLOSED && (balance.signum() != 0 || available.compareTo(balance) != 0)) {
            throw new RuleViolationException(409, "ACCOUNT_NOT_EMPTY",
                    "Account " + id + " can only be closed with a zero balance and no money on hold");
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public BigDecimal onHold() {
        return balance.subtract(available);
    }

    public String getId() { return id; }
    public String getHolderName() { return holderName; }
    public String getCurrency() { return currency; }
    public BigDecimal getBalance() { return balance; }
    public BigDecimal getAvailable() { return available; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
