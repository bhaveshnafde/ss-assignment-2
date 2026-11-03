package com.moneytransfer.ledger.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One balanced double-entry journal. {@code @Immutable}: Hibernate never issues an UPDATE for it,
 * and on PostgreSQL a trigger refuses UPDATE and DELETE as well. There are no setters.
 */
@Entity
@Table(name = "journals")
@Immutable
public class Journal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String transferId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JournalType type;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Set only on a REVERSAL: the id of the journal it cancels. */
    private Long reversesJournalId;

    @Column(nullable = false)
    private Instant postedAt;

    @OneToMany(mappedBy = "journal", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo")
    private List<JournalEntry> entries = new ArrayList<>();

    protected Journal() {
        // for JPA
    }

    /** A new TRANSFER journal. Call {@link JournalRules#check} first. */
    public static Journal forTransfer(String transferId, String currency, List<EntryLine> lines) {
        Journal j = new Journal();
        j.transferId = Objects.requireNonNull(transferId);
        j.type = JournalType.TRANSFER;
        j.currency = Objects.requireNonNull(currency);
        j.postedAt = Instant.now();
        int lineNo = 1;
        for (EntryLine line : lines) {
            j.entries.add(new JournalEntry(j, lineNo++, line.accountId(), line.direction(), line.amount()));
        }
        return j;
    }

    /**
     * The REVERSAL of this journal: the same lines with every direction swapped, so together the
     * two journals net to zero for every account.
     */
    public Journal reversal() {
        if (type != JournalType.TRANSFER) {
            throw new IllegalStateException("only a TRANSFER journal can be reversed");
        }
        if (id == null) {
            throw new IllegalStateException("journal must be saved before it can be reversed");
        }
        Journal r = new Journal();
        r.transferId = transferId;
        r.type = JournalType.REVERSAL;
        r.currency = currency;
        r.reversesJournalId = id;
        r.postedAt = Instant.now();
        for (JournalEntry e : entries) {
            r.entries.add(new JournalEntry(r, e.getLineNo(), e.getAccountId(), e.getDirection().opposite(), e.getAmount()));
        }
        return r;
    }

    public List<EntryLine> lines() {
        return entries.stream().map(e -> new EntryLine(e.getAccountId(), e.getDirection(), e.getAmount())).toList();
    }

    /** Readable id used in the API, e.g. J-000123. */
    public static String publicId(Long id) {
        return id == null ? null : "J-%06d".formatted(id);
    }

    public String journalId() { return publicId(id); }
    public Long getId() { return id; }
    public String getTransferId() { return transferId; }
    public JournalType getType() { return type; }
    public String getCurrency() { return currency; }
    public Long getReversesJournalId() { return reversesJournalId; }
    public Instant getPostedAt() { return postedAt; }
    public List<JournalEntry> getEntries() { return Collections.unmodifiableList(entries); }
}
