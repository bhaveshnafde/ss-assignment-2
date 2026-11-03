package com.moneytransfer.ledger.domain;

import java.math.BigDecimal;

/** One line of a journal as requested: which account, which side, how much. */
public record EntryLine(String accountId, Direction direction, BigDecimal amount) { }
