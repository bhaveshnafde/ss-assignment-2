package com.moneytransfer.account.domain;

import java.math.BigDecimal;

/**
 * Checks a hold request against the accounts before any money is touched.
 *
 * BR4: source and destination differ, both are ACTIVE, and both use the transfer's currency.
 * Amount sanity: greater than 0 with at most 2 decimals.
 *
 * BR2 (enough available money) is NOT checked here. Checking it in Java would be the
 * check-then-update race; the database checks it inside the atomic UPDATE instead.
 */
public final class HoldRules {

    private HoldRules() { }

    /** @throws RuleViolationException with the first rule the request breaks */
    public static void check(Account source, Account destination, BigDecimal amount, String currency) {
        if (amount.signum() <= 0) {
            throw new RuleViolationException(422, "INVALID_AMOUNT", "amount must be greater than 0");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new RuleViolationException(422, "INVALID_AMOUNT", "amount may have at most 2 decimal places");
        }
        if (source.getId().equals(destination.getId())) {
            throw new RuleViolationException(422, "SAME_ACCOUNT", "source and destination accounts must be different");
        }
        if (!source.isActive()) {
            throw new RuleViolationException(422, "ACCOUNT_NOT_ACTIVE",
                    "source account " + source.getId() + " is " + source.getStatus());
        }
        if (!destination.isActive()) {
            throw new RuleViolationException(422, "ACCOUNT_NOT_ACTIVE",
                    "destination account " + destination.getId() + " is " + destination.getStatus());
        }
        if (!source.getCurrency().equalsIgnoreCase(currency) || !destination.getCurrency().equalsIgnoreCase(currency)) {
            throw new RuleViolationException(422, "CURRENCY_MISMATCH",
                    "both accounts must hold " + currency.toUpperCase() + " (source " + source.getCurrency()
                            + ", destination " + destination.getCurrency() + ")");
        }
    }
}
