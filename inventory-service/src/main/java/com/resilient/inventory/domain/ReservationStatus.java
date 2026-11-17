package com.resilient.inventory.domain;

public enum ReservationStatus {
    /** Stock is held for the order. */
    RESERVED,
    /** There was not enough stock; remembered so a repeated command gets the same answer. */
    REJECTED,
    /** Stock was given back, or the order was cancelled before the reserve command arrived. */
    RELEASED
}
