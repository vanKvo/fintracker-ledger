package com.fintracker.ledger.transaction.exception;

public class TooManyTagsException extends RuntimeException {
    public TooManyTagsException(int max) {
        super("A transaction cannot have more than %d tags.".formatted(max));
    }
}
