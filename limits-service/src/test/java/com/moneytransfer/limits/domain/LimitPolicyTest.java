package com.moneytransfer.limits.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** BR1 (per-transfer cap, checked again here) and validation of personal limits. */
class LimitPolicyTest {

    private final LimitPolicy policy = new LimitPolicy(new BigDecimal("200000.00"), new BigDecimal("100000.00"), false);

    @Test
    void BR1_amountAtTheCapIsAllowed() {
        policy.checkPerTransfer(new BigDecimal("100000.00"));
    }

    @Test
    void BR1_amountAboveTheCapIsRejected() {
        RuleViolationException e = assertThrows(RuleViolationException.class,
                () -> policy.checkPerTransfer(new BigDecimal("100000.01")));
        assertEquals("LIMIT_EXCEEDED", e.code());
        assertEquals(422, e.httpStatus());
    }

    @Test
    void limitsMustBePositive() {
        assertEquals("INVALID_LIMIT", assertThrows(RuleViolationException.class,
                () -> new LimitPolicy(BigDecimal.ZERO, BigDecimal.ONE, true)).code());
    }

    @Test
    void perTransferLimitCannotExceedDailyLimit() {
        assertEquals("INVALID_LIMIT", assertThrows(RuleViolationException.class,
                () -> new LimitPolicy(new BigDecimal("1000"), new BigDecimal("1000.01"), true)).code());
    }

    @Test
    void limitsMayHaveAtMostTwoDecimals() {
        assertEquals("INVALID_LIMIT", assertThrows(RuleViolationException.class,
                () -> new LimitPolicy(new BigDecimal("1000.001"), new BigDecimal("10"), true)).code());
    }
}
