package com.moneytransfer.account.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * ACTIVE  <-> FROZEN   (an operator can freeze and unfreeze)
 * ACTIVE / FROZEN -> CLOSED   (final; only allowed when the account is empty, see Account.changeStatus)
 *
 * Only ACTIVE accounts can send or receive new transfers (BR4).
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED;

    public Set<AccountStatus> allowedNext() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(FROZEN, CLOSED);
            case FROZEN -> EnumSet.of(ACTIVE, CLOSED);
            case CLOSED -> EnumSet.noneOf(AccountStatus.class);
        };
    }

    public boolean canTransitionTo(AccountStatus next) {
        return allowedNext().contains(next);
    }
}
