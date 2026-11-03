package com.moneytransfer.limits.domain;

/**
 * Reservation lifecycle (rule LM1): RESERVED -> RELEASED, once. A released reservation is final and
 * its amount has been given back to the day's usage exactly once.
 */
public enum ReservationStatus {
    RESERVED,
    RELEASED;

    public boolean canTransitionTo(ReservationStatus next) {
        return this == RESERVED && next == RELEASED;
    }
}
