package com.moneytransfer.limits.domain;

import java.math.BigDecimal;
import java.time.Duration;

/** Checks that need no database: amount sanity, and the velocity (fraud) rule F1. */
public final class LimitRules {

    private LimitRules() { }

    public static void checkAmount(BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new RuleViolationException(422, "INVALID_AMOUNT", "amount must be greater than 0");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new RuleViolationException(422, "INVALID_AMOUNT", "amount may have at most 2 decimal places");
        }
    }

    /**
     * F1 (fraud): an account may start at most {@code maxTransfers} transfers within {@code window}.
     * A burst of transfers in seconds is a common sign of a compromised account.
     *
     * @param recentIncludingThis reservations created for the account within the window, this one included
     * @param maxTransfers        0 switches the rule off
     */
    public static void checkVelocity(long recentIncludingThis, int maxTransfers, Duration window) {
        if (maxTransfers > 0 && recentIncludingThis > maxTransfers) {
            throw new RuleViolationException(422, "VELOCITY_LIMIT",
                    "more than " + maxTransfers + " transfers within " + window.toSeconds() + " seconds; try again later");
        }
    }

    /** Message for a refused daily reservation, with the numbers the customer needs. */
    public static RuleViolationException dailyLimitExceeded(BigDecimal dailyLimit, BigDecimal used, BigDecimal requested) {
        return new RuleViolationException(422, "LIMIT_EXCEEDED",
                "daily limit " + dailyLimit.toPlainString() + ", used " + used.toPlainString()
                        + ", requested " + requested.toPlainString());
    }
}
