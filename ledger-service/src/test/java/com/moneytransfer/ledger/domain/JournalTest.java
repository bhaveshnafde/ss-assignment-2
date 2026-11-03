package com.moneytransfer.ledger.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** L1: corrections are reversals (mirror images), never edits. */
class JournalTest {

    private static Journal savedTransferJournal(long id) throws Exception {
        Journal j = Journal.forTransfer("t-1", "INR", List.of(
                new EntryLine("ACC-1", Direction.DEBIT, new BigDecimal("250.00")),
                new EntryLine("ACC-2", Direction.CREDIT, new BigDecimal("250.00"))));
        Field f = Journal.class.getDeclaredField("id");   // as if the database had assigned it
        f.setAccessible(true);
        f.set(j, id);
        return j;
    }

    @Test
    void transferJournalKeepsLinesInOrderWithLineNumbers() throws Exception {
        Journal j = savedTransferJournal(7);
        assertEquals(JournalType.TRANSFER, j.getType());
        assertEquals(2, j.getEntries().size());
        assertEquals(1, j.getEntries().get(0).getLineNo());
        assertEquals("ACC-1", j.getEntries().get(0).getAccountId());
        assertEquals("J-000007", j.journalId());
        assertNull(j.getReversesJournalId());
    }

    @Test
    void L1_reversalSwapsEveryDirectionAndKeepsAmounts() throws Exception {
        Journal original = savedTransferJournal(7);
        Journal reversal = original.reversal();

        assertEquals(JournalType.REVERSAL, reversal.getType());
        assertEquals("t-1", reversal.getTransferId());
        assertEquals(Long.valueOf(7), reversal.getReversesJournalId());
        assertEquals(List.of(
                new EntryLine("ACC-1", Direction.CREDIT, new BigDecimal("250.00")),
                new EntryLine("ACC-2", Direction.DEBIT, new BigDecimal("250.00"))), reversal.lines());
    }

    @Test
    void L1_reversalPlusOriginalNetsToZeroForEveryAccount() throws Exception {
        Journal original = savedTransferJournal(7);
        Journal reversal = original.reversal();
        for (String account : List.of("ACC-1", "ACC-2")) {
            BigDecimal net = BigDecimal.ZERO;
            for (Journal j : List.of(original, reversal)) {
                for (EntryLine l : j.lines()) {
                    if (l.accountId().equals(account)) {
                        net = l.direction() == Direction.DEBIT ? net.subtract(l.amount()) : net.add(l.amount());
                    }
                }
            }
            assertEquals(0, net.signum(), account + " should net to zero");
        }
    }

    @Test
    void L1_reversalIsItselfBalanced() throws Exception {
        JournalRules.check(savedTransferJournal(7).reversal().lines());
    }

    @Test
    void aReversalCannotBeReversed() throws Exception {
        Journal reversal = savedTransferJournal(7).reversal();
        assertThrows(IllegalStateException.class, reversal::reversal);
    }

    @Test
    void anUnsavedJournalCannotBeReversed() {
        Journal unsaved = Journal.forTransfer("t-2", "INR", List.of(
                new EntryLine("ACC-1", Direction.DEBIT, BigDecimal.TEN),
                new EntryLine("ACC-2", Direction.CREDIT, BigDecimal.TEN)));
        assertThrows(IllegalStateException.class, unsaved::reversal);
    }
}
