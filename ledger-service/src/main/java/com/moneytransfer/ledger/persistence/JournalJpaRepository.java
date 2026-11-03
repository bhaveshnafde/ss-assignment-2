package com.moneytransfer.ledger.persistence;

import com.moneytransfer.ledger.domain.Journal;
import com.moneytransfer.ledger.domain.JournalType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JournalJpaRepository extends JpaRepository<Journal, Long> {

    /** Loads the entries in the same query, so they can be read after the transaction ends. */
    @EntityGraph(attributePaths = "entries")
    Optional<Journal> findByTransferIdAndType(String transferId, JournalType type);

    /** The transfer's journal, then its reversal if any. */
    @EntityGraph(attributePaths = "entries")
    List<Journal> findByTransferIdOrderByIdAsc(String transferId);
}
