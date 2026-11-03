package com.moneytransfer.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** BR5: a journal balances, has both sides, and sane amounts. */
class JournalRulesTest {

    private static EntryLine debit(String account, String amount) {
        return new EntryLine(account, Direction.DEBIT, new BigDecimal(amount));
    }

    private static EntryLine credit(String account, String amount) {
        return new EntryLine(account, Direction.CREDIT, new BigDecimal(amount));
    }

    private static String violation(List<EntryLine> lines) {
        return assertThrows(RuleViolationException.class, () -> JournalRules.check(lines)).code();
    }

    @Test
    void balancedTwoLineJournalPasses() {
        JournalRules.check(List.of(debit("ACC-1", "100.00"), credit("ACC-2", "100.00")));
    }

    @Test
    void balancedMultiLineJournalPasses() {
        JournalRules.check(List.of(debit("ACC-1", "150"), credit("ACC-2", "100"), credit("ACC-3", "50")));
    }

    @Test
    void BR5_debitsNotEqualToCreditsIsRejected() {
        assertEquals("UNBALANCED_JOURNAL", violation(List.of(debit("ACC-1", "100"), credit("ACC-2", "90"))));
    }

    @Test
    void BR5_oneSidedJournalIsRejected() {
        assertEquals("UNBALANCED_JOURNAL", violation(List.of(debit("ACC-1", "100"), debit("ACC-2", "100"))));
        assertEquals("UNBALANCED_JOURNAL", violation(List.of()));
    }

    @Test
    void BR5_zeroOrNegativeAmountIsRejected() {
        assertEquals("INVALID_ENTRY", violation(List.of(debit("ACC-1", "0"), credit("ACC-2", "0"))));
        assertEquals("INVALID_ENTRY", violation(List.of(debit("ACC-1", "-5"), credit("ACC-2", "-5"))));
    }

    @Test
    void BR5_moreThanTwoDecimalsIsRejected() {
        assertEquals("INVALID_ENTRY", violation(List.of(debit("ACC-1", "1.005"), credit("ACC-2", "1.005"))));
    }

    @Test
    void BR5_accountBothDebitedAndCreditedIsRejected() {
        assertEquals("INVALID_ENTRY", violation(List.of(debit("ACC-1", "100"), credit("ACC-1", "100"))));
    }

    @Test
    void canonicalFormIgnoresLineOrderAmountFormatAndCurrencyCase() {
        assertEquals(
                JournalRules.canonical("INR", List.of(debit("ACC-1", "100"), credit("ACC-2", "100"))),
                JournalRules.canonical("inr", List.of(credit("ACC-2", "100.00"), debit("ACC-1", "100.0"))));
    }

    @Test
    void canonicalFormDiffersWhenTheJournalDiffers() {
        assertNotEquals(
                JournalRules.canonical("INR", List.of(debit("ACC-1", "100"), credit("ACC-2", "100"))),
                JournalRules.canonical("INR", List.of(debit("ACC-1", "101"), credit("ACC-2", "101"))));
    }
}
