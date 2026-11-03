package com.moneytransfer.account.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * DELIBERATELY BROKEN. Used only when HOLD_STRATEGY=naive, to show in the Challenge 1 demo
 * why "read the balance, check it in Java, then write" over-spends under concurrency.
 *
 * Two requests both read available = 100, both pass the check, both write available = 0:
 * two holds of 100 now exist against 100 of money, and nothing in the database noticed,
 * because each write on its own looks valid.
 */
@Component
public class NaiveReservation {

    private final JdbcTemplate jdbc;

    public NaiveReservation(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int reserve(String accountId, BigDecimal amount, long pauseMillis) {
        BigDecimal available = jdbc.queryForObject(
                "SELECT available FROM accounts WHERE id = ? AND status = 'ACTIVE'", BigDecimal.class, accountId);
        if (available == null || available.compareTo(amount) < 0) {          // check ...
            return 0;
        }
        pause(pauseMillis);                                                   // ... other requests read the same value meanwhile ...
        return jdbc.update("UPDATE accounts SET available = ? WHERE id = ?",  // ... then update with a stale value
                available.subtract(amount), accountId);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
