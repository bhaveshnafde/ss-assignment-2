package com.moneytransfer.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** BR4: both accounts ACTIVE, different, same currency as the transfer; amount sane. */
class HoldRulesTest {

    private static Account account(String id, String currency) {
        return Account.open(id, "Holder " + id, currency, new BigDecimal("1000.00"));
    }

    private static String violation(Account source, Account destination, String amount, String currency) {
        return assertThrows(RuleViolationException.class,
                () -> HoldRules.check(source, destination, new BigDecimal(amount), currency)).code();
    }

    @Test
    void validRequestPasses() {
        HoldRules.check(account("ACC-1", "INR"), account("ACC-2", "INR"), new BigDecimal("100.50"), "inr");
    }

    @Test
    void BR4_frozenSourceIsRejected() {
        Account source = account("ACC-1", "INR");
        source.changeStatus(AccountStatus.FROZEN);
        assertEquals("ACCOUNT_NOT_ACTIVE", violation(source, account("ACC-2", "INR"), "10", "INR"));
    }

    @Test
    void BR4_frozenDestinationIsRejected() {
        Account destination = account("ACC-2", "INR");
        destination.changeStatus(AccountStatus.FROZEN);
        assertEquals("ACCOUNT_NOT_ACTIVE", violation(account("ACC-1", "INR"), destination, "10", "INR"));
    }

    @Test
    void BR4_currencyMismatchIsRejected() {
        assertEquals("CURRENCY_MISMATCH", violation(account("ACC-1", "INR"), account("ACC-2", "USD"), "10", "INR"));
        assertEquals("CURRENCY_MISMATCH", violation(account("ACC-1", "INR"), account("ACC-2", "INR"), "10", "USD"));
    }

    @Test
    void BR4_sameAccountIsRejected() {
        Account a = account("ACC-1", "INR");
        assertEquals("SAME_ACCOUNT", violation(a, a, "10", "INR"));
    }

    @Test
    void zeroOrNegativeAmountIsRejected() {
        assertEquals("INVALID_AMOUNT", violation(account("ACC-1", "INR"), account("ACC-2", "INR"), "0", "INR"));
        assertEquals("INVALID_AMOUNT", violation(account("ACC-1", "INR"), account("ACC-2", "INR"), "-5", "INR"));
    }

    @Test
    void moreThanTwoDecimalsIsRejected() {
        assertEquals("INVALID_AMOUNT", violation(account("ACC-1", "INR"), account("ACC-2", "INR"), "1.005", "INR"));
    }
}
