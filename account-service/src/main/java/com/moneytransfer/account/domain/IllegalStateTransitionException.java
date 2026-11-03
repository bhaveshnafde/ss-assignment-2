package com.moneytransfer.account.domain;

public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(String what, String id, Enum<?> from, Enum<?> to) {
        super(what + " " + id + " cannot move from " + from + " to " + to);
    }
}
