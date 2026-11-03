package com.moneytransfer.limits.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/** An account's personal limits, replacing the defaults (e.g. a new customer with a lower limit). */
@Entity
@Table(name = "account_limits")
public class AccountLimit {

    @Id
    @Column(length = 64)
    private String accountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal perTransferLimit;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected AccountLimit() {
        // for JPA
    }

    public static AccountLimit of(String accountId, LimitPolicy policy, Instant now) {
        AccountLimit a = new AccountLimit();
        a.accountId = accountId;
        a.apply(policy, now);
        return a;
    }

    /** The policy is validated by its constructor before it gets here. */
    public void apply(LimitPolicy policy, Instant now) {
        this.dailyLimit = policy.dailyLimit();
        this.perTransferLimit = policy.perTransferLimit();
        this.updatedAt = now;
    }

    public LimitPolicy toPolicy() {
        return new LimitPolicy(dailyLimit, perTransferLimit, true);
    }

    public String getAccountId() { return accountId; }
    public Instant getUpdatedAt() { return updatedAt; }
}
