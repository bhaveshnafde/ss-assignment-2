package com.moneytransfer.ledger.domain;

/** TRANSFER records a completed transfer; REVERSAL cancels one by posting the mirror image. */
public enum JournalType {
    TRANSFER,
    REVERSAL
}
