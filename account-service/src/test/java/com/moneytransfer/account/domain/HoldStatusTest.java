package com.moneytransfer.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule A1: a hold is captured or released, never both, and never twice. */
class HoldStatusTest {

    private static Hold newHold() {
        return Hold.place("t-1", "ACC-1", "ACC-2", new BigDecimal("100.00"), "INR");
    }

    @Test
    void A1_heldCanBeCapturedOrReleased() {
        assertTrue(HoldStatus.HELD.canTransitionTo(HoldStatus.CAPTURED));
        assertTrue(HoldStatus.HELD.canTransitionTo(HoldStatus.RELEASED));
    }

    @Test
    void A1_finalStatesAcceptNothing() {
        for (HoldStatus next : HoldStatus.values()) {
            assertFalse(HoldStatus.CAPTURED.canTransitionTo(next));
            assertFalse(HoldStatus.RELEASED.canTransitionTo(next));
        }
    }

    @Test
    void A1_capturedHoldCannotBeReleased() {
        Hold h = newHold();
        h.markCaptured();
        assertThrows(IllegalStateTransitionException.class, h::markReleased);
        assertEquals(HoldStatus.CAPTURED, h.getStatus());
    }

    @Test
    void A1_releasedHoldCannotBeCaptured() {
        Hold h = newHold();
        h.markReleased();
        assertThrows(IllegalStateTransitionException.class, h::markCaptured);
    }

    @Test
    void repeatedRequestIsRecognisedEvenWithDifferentAmountFormatting() {
        Hold h = newHold();
        assertTrue(h.sameRequest("ACC-1", "ACC-2", new BigDecimal("100"), "inr"));
        assertFalse(h.sameRequest("ACC-1", "ACC-2", new BigDecimal("101"), "INR"));
        assertFalse(h.sameRequest("ACC-1", "ACC-3", new BigDecimal("100"), "INR"));
    }
}
