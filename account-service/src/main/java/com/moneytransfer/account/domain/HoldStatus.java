package com.moneytransfer.account.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Hold lifecycle (rule A1):
 * <pre>
 *   HELD -> CAPTURED   money moved to the destination (final)
 *   HELD -> RELEASED   money given back to the source (final)
 * </pre>
 * A hold can never be both captured and released, so money is never moved and refunded twice.
 */
public enum HoldStatus {
    HELD,
    CAPTURED,
    RELEASED;

    public boolean isFinal() {
        return this != HELD;
    }

    public Set<HoldStatus> allowedNext() {
        return this == HELD ? EnumSet.of(CAPTURED, RELEASED) : EnumSet.noneOf(HoldStatus.class);
    }

    public boolean canTransitionTo(HoldStatus next) {
        return allowedNext().contains(next);
    }
}
