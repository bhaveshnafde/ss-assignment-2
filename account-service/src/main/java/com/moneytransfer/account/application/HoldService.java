package com.moneytransfer.account.application;

import com.moneytransfer.account.domain.Hold;
import com.moneytransfer.account.domain.RuleViolationException;
import com.moneytransfer.account.persistence.HoldJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Makes "place hold" safe to repeat. The Transfer service retries when it gets no answer, so the
 * same transferId can arrive twice, even at the same moment.
 */
@Service
public class HoldService {

    public record PlaceResult(Hold hold, boolean created) { }

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final HoldTransactions tx;
    private final HoldJpaRepository holds;

    public HoldService(HoldTransactions tx, HoldJpaRepository holds) {
        this.tx = tx;
        this.holds = holds;
    }

    public PlaceResult place(String transferId, String sourceId, String destinationId, BigDecimal amount, String currency) {
        Optional<Hold> existing = holds.findById(transferId);
        if (existing.isPresent()) {
            return repeat(existing.get(), sourceId, destinationId, amount, currency);
        }
        try {
            return new PlaceResult(tx.placeNew(transferId, sourceId, destinationId, amount, currency), true);
        } catch (DataIntegrityViolationException e) {
            // Another request for the same transferId inserted its hold first.
            Hold winner = findAfterConflict(transferId).orElseThrow(() -> e);
            return repeat(winner, sourceId, destinationId, amount, currency);
        }
    }

    private PlaceResult repeat(Hold hold, String sourceId, String destinationId, BigDecimal amount, String currency) {
        if (!hold.sameRequest(sourceId, destinationId, amount, currency)) {
            throw new RuleViolationException(422, "HOLD_MISMATCH",
                    "transfer " + hold.getTransferId() + " already has a hold with different details");
        }
        log.info("Hold for transfer {} already exists ({}); nothing changed", hold.getTransferId(), hold.getStatus());
        return new PlaceResult(hold, false);
    }

    /**
     * PostgreSQL makes the losing INSERT wait until the winner commits, so the winner's row is
     * visible at once. Some databases report the conflict earlier; looking again briefly covers that.
     */
    private Optional<Hold> findAfterConflict(String transferId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            Optional<Hold> hold = holds.findById(transferId);
            if (hold.isPresent()) {
                return hold;
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
