package com.fintracker.ledger.category.exception;

/** DP-LEDGER-CATEGORIES-02: an internal call asked for a user other than the one it acts for. */
public class InternalUserMismatchException extends RuntimeException {

    public InternalUserMismatchException() {
        super("The requested user does not match the caller's user.");
    }
}
