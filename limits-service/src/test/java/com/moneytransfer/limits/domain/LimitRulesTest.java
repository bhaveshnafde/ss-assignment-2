package com.moneytransfer.limits.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Amount sanity, the F1 velocity (fraud) rule, and the BR3 refusal message. */
class LimitRulesTest {

    @Test
    void positiveAmountWithTwoDecimalsPasses() {
        LimitRules.checkAmount(new BigDecimal("1500.50"));
    }

    @Test
    void zeroOrNegativeAmountIsRejected() {
        assertEquals("INVALID_AMOUNT", assertThrows(RuleViolationException.class,
                () -> LimitRules.checkAmount(BigDecimal.ZERO)).code());
        assertEquals("INVALID_AMOUNT", assertThrows(RuleViolationException.class,
                () -> LimitRules.checkAmount(new BigDecimal("-1"))).code());
    }

    @Test
    void moreThanTwoDecimalsIsRejected() {
        assertEquals("INVALID_AMOUNT", assertThrows(RuleViolationException.class,
                () -> LimitRules.checkAmount(new BigDecimal("1.001"))).code());
    }

    @Test
    void F1_upToTheMaximumWithinTheWindowIsAllowed() {
        LimitRules.checkVelocity(10, 10, Duration.ofSeconds(60));
    }

    @Test
    void F1_oneMoreThanTheMaximumIsRejected() {
        RuleViolationException e = assertThrows(RuleViolationException.class,
                () -> LimitRules.checkVelocity(11, 10, Duration.ofSeconds(60)));
        assertEquals("VELOCITY_LIMIT", e.code());
        assertEquals(422, e.httpStatus());
    }

    @Test
    void F1_zeroSwitchesTheRuleOff() {
        LimitRules.checkVelocity(1_000, 0, Duration.ofSeconds(60));
    }

    @Test
    void BR3_refusalTellsTheCustomerTheNumbers() {
        RuleViolationException e = LimitRules.dailyLimitExceeded(
                new BigDecimal("200000.00"), new BigDecimal("199000.00"), new BigDecimal("1500.00"));
        assertEquals("LIMIT_EXCEEDED", e.code());
        assertTrue(e.getMessage().contains("199000.00") && e.getMessage().contains("1500.00"), e.getMessage());
    }
}
