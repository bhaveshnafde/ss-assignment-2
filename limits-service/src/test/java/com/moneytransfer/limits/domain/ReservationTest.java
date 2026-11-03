package com.moneytransfer.limits.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LM1: a reservation is released at most once and remembers its day. */
class ReservationTest {

    private static final Instant NOW = Instant.parse("2026-10-30T23:59:30Z");

    private static Reservation newReservation() {
        return Reservation.reserve("t-1", "ACC-1", new BigDecimal("250.00"), "inr", LocalDate.parse("2026-10-30"), NOW);
    }

    @Test
    void newReservationIsReservedAndUpperCasesTheCurrency() {
        Reservation r = newReservation();
        assertEquals(ReservationStatus.RESERVED, r.getStatus());
        assertEquals("INR", r.getCurrency());
    }

    @Test
    void LM1_releaseMovesToReleasedAndKeepsTheOriginalDay() {
        Reservation r = newReservation();
        r.release(Instant.parse("2026-10-31T00:00:10Z"));   // after midnight
        assertEquals(ReservationStatus.RELEASED, r.getStatus());
        assertEquals(LocalDate.parse("2026-10-30"), r.getUsageDay());
    }

    @Test
    void LM1_aReservationCannotBeReleasedTwice() {
        Reservation r = newReservation();
        r.release(NOW);
        assertThrows(IllegalStateException.class, () -> r.release(NOW));
    }

    @Test
    void LM1_onlyReservedToReleasedIsAllowed() {
        assertTrue(ReservationStatus.RESERVED.canTransitionTo(ReservationStatus.RELEASED));
        assertFalse(ReservationStatus.RELEASED.canTransitionTo(ReservationStatus.RESERVED));
        assertFalse(ReservationStatus.RELEASED.canTransitionTo(ReservationStatus.RELEASED));
    }

    @Test
    void repeatedRequestIsRecognisedEvenWithDifferentAmountFormatting() {
        Reservation r = newReservation();
        assertTrue(r.sameRequest("ACC-1", new BigDecimal("250"), "INR"));
        assertFalse(r.sameRequest("ACC-1", new BigDecimal("251"), "INR"));
        assertFalse(r.sameRequest("ACC-2", new BigDecimal("250"), "INR"));
    }
}
