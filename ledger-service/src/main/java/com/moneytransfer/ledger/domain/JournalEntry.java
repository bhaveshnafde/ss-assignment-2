package com.moneytransfer.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;

/** One line of a journal. Immutable like its journal. */
@Entity
@Table(name = "journal_entries")
@Immutable
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_id")
    private Journal journal;

    @Column(nullable = false)
    private int lineNo;

    @Column(nullable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direction direction;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    protected JournalEntry() {
        // for JPA
    }

    JournalEntry(Journal journal, int lineNo, String accountId, Direction direction, BigDecimal amount) {
        this.journal = journal;
        this.lineNo = lineNo;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public Journal getJournal() { return journal; }
    public int getLineNo() { return lineNo; }
    public String getAccountId() { return accountId; }
    public Direction getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
}
