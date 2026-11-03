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
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Makes "reserve" and "release" safe to repeat. The Transfer service retries when it gets no
 * answer, so the same transferId can arrive twice, even at the same moment.
 */
@Service
public class ReservationService {

    /** The reservation plus the numbers the API reports alongside it. */
    public record Result(Reservation reservation, boolean created, LimitPolicy policy, BigDecimal usedThatDay) { }

    /** An account's limits and usage today, for GET /limits/{accountId}. */
    public record Status(String accountId, LocalDate day, LimitPolicy policy, BigDecimal usedToday,
                         long recentTransfers, int velocityMax, long velocityWindowSeconds) { }

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationTransactions tx;
    private final ReservationJpaRepository reservations;
    private final UsageRepository usage;
    private final LimitPolicyService policies;
    private final LimitsProperties properties;
    private final Clock clock;

    public ReservationService(ReservationTransactions tx, ReservationJpaRepository reservations, UsageRepository usage,
                              LimitPolicyService policies, LimitsProperties properties, Clock clock) {
        this.tx = tx;
        this.reservations = reservations;
        this.usage = usage;
        this.policies = policies;
        this.properties = properties;
        this.clock = clock;
    }

    public Result reserve(String transferId, String accountId, BigDecimal amount, String currency) {
        LimitRules.checkAmount(amount);
        LimitPolicy policy = policies.policyFor(accountId);

        Optional<Reservation> existing = reservations.findById(transferId);
        if (existing.isPresent()) {
            return repeat(existing.get(), accountId, amount, currency, policy);
        }

        policy.checkPerTransfer(amount);                       // BR1, before touching the database
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, clock.getZone());
        usage.ensureRow(accountId, day, now);
        try {
            Reservation created = tx.reserveNew(transferId, accountId, amount, currency, policy, day, now);
            return new Result(created, true, policy, usage.usedOn(accountId, day));
        } catch (DataAccessException e) {
            // Usually: another request for the same transfer inserted its reservation first.
            Reservation winner = findAfterConflict(transferId).orElseThrow(() -> e);
            return repeat(winner, accountId, amount, currency, policy);
        }
    }

    public ReservationTransactions.Outcome release(String transferId) {
        return tx.release(transferId, clock.instant());
    }

    public Reservation get(String transferId) {
        return reservations.findById(transferId)
                .orElseThrow(() -> new RuleViolationException(404, "RESERVATION_NOT_FOUND",
                        "no reservation for transfer " + transferId));
    }

    public Status status(String accountId) {
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, clock.getZone());
        long recent = reservations.countByAccountIdAndCreatedAtAfter(accountId, now.minus(properties.velocityWindow()));
        return new Status(accountId, day, policies.policyFor(accountId), usage.usedOn(accountId, day), recent,
                properties.velocityMaxTransfers(), properties.velocityWindow().toSeconds());
    }

    private Result repeat(Reservation r, String accountId, BigDecimal amount, String currency, LimitPolicy policy) {
        if (!r.sameRequest(accountId, amount, currency)) {
            throw new RuleViolationException(422, "RESERVATION_MISMATCH",
                    "transfer " + r.getTransferId() + " already has a reservation with different details");
        }
        if (r.getStatus() == ReservationStatus.RELEASED) {
            // The transfer was already undone; reserving again would resurrect it.
            throw new RuleViolationException(409, "RESERVATION_RELEASED",
                    "the reservation for transfer " + r.getTransferId() + " was released; start a new transfer");
        }
        log.info("Reservation for transfer {} already exists; nothing changed", r.getTransferId());
        return new Result(r, false, policy, usage.usedOn(r.getAccountId(), r.getUsageDay()));
    }

    /**
     * PostgreSQL makes the losing INSERT wait until the winner commits, so the winner's row is
     * visible at once. Some databases report the conflict earlier; looking again briefly covers that.
     */
    private Optional<Reservation> findAfterConflict(String transferId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            Optional<Reservation> found = reservations.findById(transferId);
            if (found.isPresent()) {
                return found;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Optional.empty();
    }
}
