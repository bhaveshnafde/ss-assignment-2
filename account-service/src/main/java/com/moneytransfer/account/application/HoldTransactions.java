package com.moneytransfer.account.application;

import com.moneytransfer.account.config.AccountProperties;
import com.moneytransfer.account.domain.Account;
import com.moneytransfer.account.domain.AccountStatus;
import com.moneytransfer.account.domain.Hold;
import com.moneytransfer.account.domain.HoldRules;
import com.moneytransfer.account.domain.HoldStatus;
import com.moneytransfer.account.domain.RuleViolationException;
import com.moneytransfer.account.persistence.AccountJpaRepository;
import com.moneytransfer.account.persistence.HoldJpaRepository;
import com.moneytransfer.account.persistence.NaiveReservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The three money operations, each in ONE database transaction: either everything in it happens
 * or nothing does. Called by {@link HoldService}, which handles repeated requests.
 */
@Component
public class HoldTransactions {

    /** What happened, so the API can answer 201 (new) or 200 (already done). */
    public record Outcome(Hold hold, boolean changed) { }

    private static final Logger log = LoggerFactory.getLogger(HoldTransactions.class);

    private final AccountJpaRepository accounts;
    private final HoldJpaRepository holds;
    private final NaiveReservation naive;
    private final AccountProperties properties;

    public HoldTransactions(AccountJpaRepository accounts, HoldJpaRepository holds, NaiveReservation naive,
                            AccountProperties properties) {
        this.accounts = accounts;
        this.holds = holds;
        this.naive = naive;
        this.properties = properties;
        if (properties.holdStrategy() == AccountProperties.HoldStrategy.NAIVE) {
            log.warn("HOLD_STRATEGY=naive: holds use read-check-write and CAN OVERSPEND. Challenge 1 demo only!");
        }
    }

    /**
     * Place a new hold:
     * 1. check BR4 (accounts exist, ACTIVE, same currency);
     * 2. INSERT the hold, which claims the transferId (the primary key stops a second hold);
     * 3. reserve the money with one conditional UPDATE (BR2).
     * If step 3 finds too little money, the exception rolls back step 2 too: no hold is left behind.
     */
    @Transactional
    public Hold placeNew(String transferId, String sourceId, String destinationId, BigDecimal amount, String currency) {
        Account source = accounts.findById(sourceId).orElseThrow(() -> notFound(sourceId));
        Account destination = accounts.findById(destinationId).orElseThrow(() -> notFound(destinationId));
        HoldRules.check(source, destination, amount, currency);

        Hold hold = holds.saveAndFlush(Hold.place(transferId, sourceId, destinationId, amount, currency.toUpperCase()));

        int reserved = properties.holdStrategy() == AccountProperties.HoldStrategy.NAIVE
                ? naive.reserve(sourceId, amount, properties.naiveDelayMs())
                : accounts.reserveIfAvailable(sourceId, amount, Instant.now(), AccountStatus.ACTIVE);
        if (reserved == 0) {
            log.info("Hold for transfer {} refused: {} has less than {} available", transferId, sourceId, amount);
            throw new RuleViolationException(409, "INSUFFICIENT_FUNDS",
                    "available balance of " + sourceId + " is less than " + amount.toPlainString());
        }
        log.info("Hold placed for transfer {}: {} {} on {}", transferId, amount, currency, sourceId);
        return hold;
    }

    /**
     * Commit the transfer: the held money leaves the source's balance and arrives in the
     * destination's balance and available, all in one transaction. Repeating it is harmless.
     */
    @Transactional
    public Outcome capture(String transferId) {
        Hold hold = holds.findForUpdate(transferId).orElseThrow(() -> holdNotFound(transferId));
        if (hold.getStatus() == HoldStatus.CAPTURED) {
            return new Outcome(hold, false);
        }
        if (hold.getStatus() == HoldStatus.RELEASED) {
            throw new RuleViolationException(409, "HOLD_NOT_ACTIVE", "hold for transfer " + transferId + " was released");
        }
        // Change the hold first: the UPDATE statements below flush it before they run.
        hold.markCaptured();
        Instant now = Instant.now();
        // Always lock the two account rows in the same (alphabetical) order. Otherwise A->B and B->A
        // captured at the same moment could each lock one row and wait forever for the other (deadlock).
        String source = hold.getSourceAccountId(), destination = hold.getDestinationAccountId();
        int updated = source.compareTo(destination) < 0
                ? accounts.debitReserved(source, hold.getAmount(), now) + accounts.credit(destination, hold.getAmount(), now)
                : accounts.credit(destination, hold.getAmount(), now) + accounts.debitReserved(source, hold.getAmount(), now);
        if (updated != 2) {
            throw new IllegalStateException("Capture of transfer " + transferId + " could not update both accounts");
        }
        log.info("Hold captured for transfer {}: {} moved from {} to {}", transferId, hold.getAmount(),
                hold.getSourceAccountId(), hold.getDestinationAccountId());
        return new Outcome(hold, true);
    }

    /** Undo a hold: the money becomes available again on the source. Repeating it is harmless. */
    @Transactional
    public Outcome release(String transferId) {
        Hold hold = holds.findForUpdate(transferId).orElseThrow(() -> holdNotFound(transferId));
        if (hold.getStatus() == HoldStatus.RELEASED) {
            return new Outcome(hold, false);
        }
        if (hold.getStatus() == HoldStatus.CAPTURED) {
            throw new RuleViolationException(409, "HOLD_ALREADY_CAPTURED",
                    "hold for transfer " + transferId + " was already captured; the money has moved");
        }
        hold.markReleased();
        accounts.unreserve(hold.getSourceAccountId(), hold.getAmount(), Instant.now());
        log.info("Hold released for transfer {}: {} back to {}", transferId, hold.getAmount(), hold.getSourceAccountId());
        return new Outcome(hold, true);
    }

    private static RuleViolationException notFound(String accountId) {
        return new RuleViolationException(404, "ACCOUNT_NOT_FOUND", "no account " + accountId);
    }

    private static RuleViolationException holdNotFound(String transferId) {
        return new RuleViolationException(404, "HOLD_NOT_FOUND", "no hold for transfer " + transferId);
    }
}
