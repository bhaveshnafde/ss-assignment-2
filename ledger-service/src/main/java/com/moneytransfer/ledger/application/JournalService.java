package com.moneytransfer.ledger.application;

import com.moneytransfer.ledger.domain.EntryLine;
import com.moneytransfer.ledger.domain.Journal;
import com.moneytransfer.ledger.domain.JournalEntry;
import com.moneytransfer.ledger.domain.JournalRules;
import com.moneytransfer.ledger.domain.JournalType;
import com.moneytransfer.ledger.domain.RuleViolationException;
import com.moneytransfer.ledger.persistence.JournalEntryJpaRepository;
import com.moneytransfer.ledger.persistence.JournalJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Makes posting and reversing safe to repeat. The Transfer service retries when it gets no answer,
 * so the same transferId can arrive twice, even at the same moment.
 */
@Service
public class JournalService {

    /** The journal to return, its reversal if there is one, and whether this request created it (201 vs 200). */
    public record Result(Journal journal, Optional<Journal> reversal, boolean created) { }

    private static final Logger log = LoggerFactory.getLogger(JournalService.class);

    private final JournalTransactions tx;
    private final JournalJpaRepository journals;
    private final JournalEntryJpaRepository entries;
    private final FaultInjection faults;

    public JournalService(JournalTransactions tx, JournalJpaRepository journals, JournalEntryJpaRepository entries,
                          FaultInjection faults) {
        this.tx = tx;
        this.journals = journals;
        this.entries = entries;
        this.faults = faults;
    }

    public Result post(String transferId, String currency, List<EntryLine> lines) {
        faults.beforePost();
        Optional<Journal> existing = journals.findByTransferIdAndType(transferId, JournalType.TRANSFER);
        if (existing.isPresent()) {
            return repeat(existing.get(), currency, lines);
        }
        try {
            Journal created = tx.postNew(transferId, currency, lines);
            faults.afterNewPost();
            return new Result(created, Optional.empty(), true);
        } catch (DataAccessException e) {
            // Usually: another request for the same transfer saved its journal first.
            Journal winner = findAfterConflict(transferId, JournalType.TRANSFER).orElseThrow(() -> e);
            return repeat(winner, currency, lines);
        }
    }

    public Result reverse(String transferId) {
        Journal original = journals.findByTransferIdAndType(transferId, JournalType.TRANSFER)
                .orElseThrow(() -> new RuleViolationException(404, "JOURNAL_NOT_FOUND",
                        "no journal for transfer " + transferId));
        Optional<Journal> existing = journals.findByTransferIdAndType(transferId, JournalType.REVERSAL);
        if (existing.isPresent()) {
            log.info("Transfer {} already reversed by {}; nothing changed", transferId, existing.get().journalId());
            return new Result(existing.get(), Optional.empty(), false);
        }
        try {
            return new Result(tx.reverseNew(original), Optional.empty(), true);
        } catch (DataAccessException e) {
            Journal winner = findAfterConflict(transferId, JournalType.REVERSAL).orElseThrow(() -> e);
            return new Result(winner, Optional.empty(), false);
        }
    }

    /** The transfer's journal, then its reversal if any. */
    public List<Journal> forTransfer(String transferId) {
        List<Journal> found = journals.findByTransferIdOrderByIdAsc(transferId);
        if (found.isEmpty()) {
            throw new RuleViolationException(404, "JOURNAL_NOT_FOUND", "no journal for transfer " + transferId);
        }
        return found;
    }

    public List<JournalEntry> entriesFor(String accountId, int limit) {
        return entries.findByAccountNewestFirst(accountId, PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    public Optional<Journal> reversalOf(String transferId) {
        return journals.findByTransferIdAndType(transferId, JournalType.REVERSAL);
    }

    private Result repeat(Journal existing, String currency, List<EntryLine> lines) {
        if (!JournalRules.canonical(existing.getCurrency(), existing.lines()).equals(JournalRules.canonical(currency, lines))) {
            throw new RuleViolationException(422, "JOURNAL_MISMATCH",
                    "transfer " + existing.getTransferId() + " already has a journal with different lines");
        }
        log.info("Journal for transfer {} already exists ({}); nothing changed",
                existing.getTransferId(), existing.journalId());
        return new Result(existing, reversalOf(existing.getTransferId()), false);
    }

    /**
     * PostgreSQL makes the losing INSERT wait until the winner commits, so the winner's row is
     * visible at once. Some databases report the conflict earlier; looking again briefly covers that.
     */
    private Optional<Journal> findAfterConflict(String transferId, JournalType type) {
        for (int attempt = 0; attempt < 20; attempt++) {
            Optional<Journal> found = journals.findByTransferIdAndType(transferId, type);
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
