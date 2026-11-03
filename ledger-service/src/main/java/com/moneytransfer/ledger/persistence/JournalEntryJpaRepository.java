package com.moneytransfer.ledger.persistence;

import com.moneytransfer.ledger.domain.JournalEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface JournalEntryJpaRepository extends JpaRepository<JournalEntry, Long> {

    /** An account's lines across all journals, newest first, with each line's journal loaded. */
    @Query("select e from JournalEntry e join fetch e.journal where e.accountId = :accountId order by e.id desc")
    List<JournalEntry> findByAccountNewestFirst(@Param("accountId") String accountId, Pageable page);
}
