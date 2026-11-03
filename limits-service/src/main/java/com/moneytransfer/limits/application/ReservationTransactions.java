package com.moneytransfer.limits.application;

import com.moneytransfer.limits.config.LimitsProperties;
import com.moneytransfer.limits.domain.LimitPolicy;
import com.moneytransfer.limits.domain.LimitRules;
import com.moneytransfer.limits.domain.Reservation;
import com.moneytransfer.limits.domain.ReservationStatus;
import com.moneytransfer.limits.domain.RuleViolationException;
import com.moneytransfer.limits.persistence.ReservationJpaRepository;
import com.moneytransfer.limits.persistence.UsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The two writes, each in ONE database transaction: everything in it happens or nothing does.
 * Called by {@link ReservationService}, which handles repeated requests.
 */
@Component
public class ReservationTransactions {

    /** What happened, so the API can answer 201 (new) / 204 (released) or say nothing changed. */
    public record Outcome(Reservation reservation, boolean changed) { }

    private static final Logger log = LoggerFactory.getLogger(ReservationTransactions.class);

    private final ReservationJpaRepository reservations;
    private final UsageRepository usage;
    private final LimitsProperties properties;

    public ReservationTransactions(ReservationJpaRepository reservations, UsageRepository usage,
                                   LimitsProperties properties) {
        this.reservations = reservations;
        this.usage = usage;
        this.properties = properties;
    }

    /**
     * 1. INSERT the reservation, which claims the transferId (the primary key stops a second one);
     * 2. add the amount to the day's usage with one conditional UPDATE (BR3);
     * 3. count the account's recent reservations (F1). This runs while step 2 still holds the account's
     *    usage row lock, so concurrent reservations for the account are counted one after another.
     * Any refusal throws, which rolls back steps 1 and 2: a refused request leaves nothing behind.
     */
    @Transactional
    public Reservation reserveNew(String transferId, String accountId, BigDecimal amount, String currency,
                                  LimitPolicy policy, LocalDate day, Instant now) {
        Reservation reservation = reservations.saveAndFlush(
                Reservation.reserve(transferId, accountId, amount, currency, day, now));

        if (usage.reserveIfWithinLimit(accountId, day, amount, policy.dailyLimit(), now) == 0) {
            BigDecimal used = usage.usedOn(accountId, day);
            log.info("Reservation for transfer {} refused: {} would exceed daily limit {} (used {})",
                    transferId, amount, policy.dailyLimit(), used);
            throw LimitRules.dailyLimitExceeded(policy.dailyLimit(), used, amount);
        }

        if (properties.velocityMaxTransfers() > 0) {
            long recent = reservations.countByAccountIdAndCreatedAtAfter(accountId, now.minus(properties.velocityWindow()));
            if (recent > properties.velocityMaxTransfers()) {
                log.warn("Reservation for transfer {} refused: {} transfers from {} in {}s (fraud rule F1)",
                        transferId, recent, accountId, properties.velocityWindow().toSeconds());
            }
            LimitRules.checkVelocity(recent, properties.velocityMaxTransfers(), properties.velocityWindow());
        }

        log.info("Reserved {} {} of {}'s daily limit for transfer {}", amount, currency, accountId, transferId);
        return reservation;
    }

    /** Undo a reservation: the amount goes back to the day it was taken from. Repeating it is harmless. */
    @Transactional
    public Outcome release(String transferId, Instant now) {
        Reservation r = reservations.findForUpdate(transferId)
                .orElseThrow(() -> new RuleViolationException(404, "RESERVATION_NOT_FOUND",
                        "no reservation for transfer " + transferId));
        if (r.getStatus() == ReservationStatus.RELEASED) {
            return new Outcome(r, false);
        }
        r.release(now);
        if (usage.unreserve(r.getAccountId(), r.getUsageDay(), r.getAmount(), now) != 1) {
            throw new IllegalStateException("usage for " + r.getAccountId() + " on " + r.getUsageDay()
                    + " is lower than the reservation being released");
        }
        log.info("Released {} of {}'s limit for transfer {}", r.getAmount(), r.getAccountId(), transferId);
        return new Outcome(r, true);
    }
}
