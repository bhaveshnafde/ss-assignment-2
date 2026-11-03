package com.moneytransfer.ledger.application;

import com.moneytransfer.ledger.domain.EntryLine;
import com.moneytransfer.ledger.domain.Journal;
import com.moneytransfer.ledger.domain.JournalRules;
import com.moneytransfer.ledger.persistence.JournalJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * The two writes, each in ONE database transaction: the journal row and all its lines are saved
 * together or not at all. Called by {@link JournalService}, which handles repeated requests.
 */
@Component
public class JournalTransactions {

    private static final Logger log = LoggerFactory.getLogger(JournalTransactions.class);

    private final JournalJpaRepository journals;

    public JournalTransactions(JournalJpaRepository journals) {
        this.journals = journals;
    }

    /**
     * Saves a new TRANSFER journal. The UNIQUE (transfer_id, type) constraint makes a second one
     * for the same transfer fail, even if both requests arrive at the same moment.
     */
    @Transactional
    public Journal postNew(String transferId, String currency, List<EntryLine> lines) {
        JournalRules.check(lines);
        Journal saved = journals.saveAndFlush(Journal.forTransfer(transferId, currency.toUpperCase(Locale.ROOT), lines));
        log.info("Journal {} posted for transfer {} ({} lines)", saved.journalId(), transferId, lines.size());
        return saved;
    }

    /** Saves the mirror-image REVERSAL of a journal. At most one per transfer (same constraint). */
    @Transactional
    public Journal reverseNew(Journal original) {
        Journal saved = journals.saveAndFlush(original.reversal());
        log.info("Journal {} reversed by {} for transfer {}", original.journalId(), saved.journalId(),
                original.getTransferId());
        return saved;
    }
}
