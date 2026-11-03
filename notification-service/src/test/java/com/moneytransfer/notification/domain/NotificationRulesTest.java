package com.moneytransfer.notification.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NotificationRulesTest {

    @Test
    void validRequestPasses() {
        NotificationRules.check("TRANSFER_COMPLETED", "You sent 1500.00 INR to ACC-2002.");
    }

    @Test
    void templateMustBeUpperCaseWords() {
        assertEquals("INVALID_TEMPLATE", assertThrows(RuleViolationException.class,
                () -> NotificationRules.check("transfer-completed", "hi")).code());
        assertEquals("INVALID_TEMPLATE", assertThrows(RuleViolationException.class,
                () -> NotificationRules.check("1_STARTS_WITH_DIGIT", "hi")).code());
    }

    @Test
    void messageMustNotBeBlankOrTooLong() {
        assertEquals("INVALID_MESSAGE", assertThrows(RuleViolationException.class,
                () -> NotificationRules.check("TRANSFER_COMPLETED", "   ")).code());
        assertEquals("INVALID_MESSAGE", assertThrows(RuleViolationException.class,
                () -> NotificationRules.check("TRANSFER_COMPLETED", "x".repeat(501))).code());
    }
}
