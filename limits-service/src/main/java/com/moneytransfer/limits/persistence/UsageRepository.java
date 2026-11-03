package com.moneytransfer.limits.persistence;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The daily_usage table, in plain SQL on purpose: the reservation must be ONE conditional UPDATE so
 * the database checks the limit and adds the amount in a single step, under the row lock.
 */
@Repository
public class UsageRepository {

    private final JdbcTemplate jdbc;

    public UsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes sure the account has a row for the day, so the UPDATE below has something to lock.
     * Called OUTSIDE the reservation transaction: if another request created the row a moment
     * earlier, this insert fails harmlessly and the row is there either way.
     */
    public void ensureRow(String accountId, LocalDate day, Instant now) {
        try {
            jdbc.update("INSERT INTO daily_usage (account_id, usage_day, used, updated_at) VALUES (?, ?, 0, ?)",
                    accountId, day, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        } catch (DataAccessException alreadyThere) {
            // duplicate key: the row exists, which is all we need
        }
    }

    /**
     * BR3 / contended resource: add the amount only if the day stays within the limit.
     * Concurrent reservations for the same account queue on the row lock, and each one re-checks
     * "used + amount <= limit" against the latest committed value.
     *
     * @return 1 if reserved, 0 if it would exceed the limit
     */
    public int reserveIfWithinLimit(String accountId, LocalDate day, BigDecimal amount, BigDecimal dailyLimit, Instant now) {
        return jdbc.update("""
                UPDATE daily_usage SET used = used + ?, updated_at = ?
                WHERE account_id = ? AND usage_day = ? AND used + ? <= ?
                """, amount, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), accountId, day, amount, dailyLimit);
    }

    /** Give a released reservation's amount back to the day it was taken from. */
    public int unreserve(String accountId, LocalDate day, BigDecimal amount, Instant now) {
        return jdbc.update("""
                UPDATE daily_usage SET used = used - ?, updated_at = ?
                WHERE account_id = ? AND usage_day = ? AND used >= ?
                """, amount, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), accountId, day, amount);
    }

    public BigDecimal usedOn(String accountId, LocalDate day) {
        return jdbc.query("SELECT used FROM daily_usage WHERE account_id = ? AND usage_day = ?",
                        (rs, i) -> rs.getBigDecimal("used"), accountId, day)
                .stream().findFirst().orElse(BigDecimal.ZERO.setScale(2));
    }
}
