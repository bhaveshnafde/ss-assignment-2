package com.moneytransfer.limits.application;

import com.moneytransfer.limits.config.LimitsProperties;
import com.moneytransfer.limits.domain.AccountLimit;
import com.moneytransfer.limits.domain.LimitPolicy;
import com.moneytransfer.limits.persistence.AccountLimitJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;

/** Which limits apply to an account: its own override if it has one, otherwise the defaults. */
@Service
public class LimitPolicyService {

    private static final Logger log = LoggerFactory.getLogger(LimitPolicyService.class);

    private final AccountLimitJpaRepository overrides;
    private final LimitsProperties properties;
    private final Clock clock;

    public LimitPolicyService(AccountLimitJpaRepository overrides, LimitsProperties properties, Clock clock) {
        this.overrides = overrides;
        this.properties = properties;
        this.clock = clock;
    }

    public LimitPolicy policyFor(String accountId) {
        return overrides.findById(accountId)
                .map(AccountLimit::toPolicy)
                .orElseGet(this::defaults);
    }

    public LimitPolicy defaults() {
        return new LimitPolicy(properties.dailyLimit(), properties.perTransferLimit(), false);
    }

    @Transactional
    public LimitPolicy setOverride(String accountId, BigDecimal dailyLimit, BigDecimal perTransferLimit) {
        LimitPolicy policy = new LimitPolicy(dailyLimit, perTransferLimit, true);   // validates
        AccountLimit row = overrides.findById(accountId)
                .map(existing -> { existing.apply(policy, clock.instant()); return existing; })
                .orElseGet(() -> AccountLimit.of(accountId, policy, clock.instant()));
        overrides.save(row);
        log.info("Limits for {} set to daily {} / per transfer {}", accountId, dailyLimit, perTransferLimit);
        return policy;
    }

    @Transactional
    public void clearOverride(String accountId) {
        overrides.findById(accountId).ifPresent(overrides::delete);
        log.info("Limits for {} back to defaults", accountId);
    }
}
