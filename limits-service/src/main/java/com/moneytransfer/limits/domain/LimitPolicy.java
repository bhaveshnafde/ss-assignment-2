package com.moneytransfer.limits.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The limits that apply to one account: the defaults, or the account's own override.
 *
 * @param dailyLimit       BR3: most the account may send in one UTC day, pending transfers included
 * @param perTransferLimit BR1: most a single transfer may be
 * @param personal         true when the account has its own override instead of the defaults
 */
public record LimitPolicy(BigDecimal dailyLimit, BigDecimal perTransferLimit, boolean personal) {

    public LimitPolicy {
        Objects.requireNonNull(dailyLimit);
        Objects.requireNonNull(perTransferLimit);
        if (dailyLimit.signum() <= 0 || perTransferLimit.signum() <= 0) {
            throw new RuleViolationException(422, "INVALID_LIMIT", "limits must be greater than 0");
        }
        if (dailyLimit.stripTrailingZeros().scale() > 2 || perTransferLimit.stripTrailingZeros().scale() > 2) {
            throw new RuleViolationException(422, "INVALID_LIMIT", "limits may have at most 2 decimal places");
        }
        if (perTransferLimit.compareTo(dailyLimit) > 0) {
            throw new RuleViolationException(422, "INVALID_LIMIT", "perTransferLimit must not exceed dailyLimit");
        }
    }

    /** BR1 (checked here a second time, after Transfer): one transfer may not exceed the cap. */
    public void checkPerTransfer(BigDecimal amount) {
        if (amount.compareTo(perTransferLimit) > 0) {
            throw new RuleViolationException(422, "LIMIT_EXCEEDED",
                    "amount " + amount.toPlainString() + " is above the per-transfer limit of " + perTransferLimit.toPlainString());
        }
    }
}
