package com.fintracker.ledger.statement.exception;

import java.util.UUID;

public class StatementNotFoundException extends RuntimeException {
    public StatementNotFoundException(UUID id) {
        super("Statement not found: " + id);
    }
}
