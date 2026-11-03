package com.moneytransfer.ledger.domain;

/** Which side of a double entry a line is on. A reversal swaps every line's direction. */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
