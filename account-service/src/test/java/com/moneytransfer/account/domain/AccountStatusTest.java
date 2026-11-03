package com.moneytransfer.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule A2: an account is closed only when empty; CLOSED is final. */
class AccountStatusTest {

    @Test
    void newAccountIsActiveWithEverythingAvailable() {
        Account a = Account.open("ACC-1", "Asha", "INR", new BigDecimal("250.00"));
        assertEquals(AccountStatus.ACTIVE, a.getStatus());
        assertEquals(0, a.getAvailable().compareTo(a.getBalance()));
        assertEquals(0, a.onHold().signum());
    }

    @Test
    void negativeOpeningBalanceIsRejected() {
        RuleViolationException e = assertThrows(RuleViolationException.class,
                () -> Account.open("ACC-1", "Asha", "INR", new BigDecimal("-1")));
        assertEquals("NEGATIVE_INITIAL_BALANCE", e.code());
    }

    @Test
    void canFreezeAndUnfreeze() {
        Account a = Account.open("ACC-1", "Asha", "INR", new BigDecimal("10"));
        a.changeStatus(AccountStatus.FROZEN);
        assertFalse(a.isActive());
        a.changeStatus(AccountStatus.ACTIVE);
        assertTrue(a.isActive());
    }

    @Test
    void A2_accountWithMoneyCannotBeClosed() {
        Account a = Account.open("ACC-1", "Asha", "INR", new BigDecimal("10"));
        RuleViolationException e = assertThrows(RuleViolationException.class, () -> a.changeStatus(AccountStatus.CLOSED));
        assertEquals("ACCOUNT_NOT_EMPTY", e.code());
        assertEquals(AccountStatus.ACTIVE, a.getStatus());
    }

    @Test
    void A2_emptyAccountCanBeClosed_andClosedIsFinal() {
        Account a = Account.open("ACC-1", "Asha", "INR", BigDecimal.ZERO);
        a.changeStatus(AccountStatus.CLOSED);
        assertEquals(AccountStatus.CLOSED, a.getStatus());

        RuleViolationException e = assertThrows(RuleViolationException.class, () -> a.changeStatus(AccountStatus.ACTIVE));
        assertEquals("INVALID_STATUS_CHANGE", e.code());
    }
}
