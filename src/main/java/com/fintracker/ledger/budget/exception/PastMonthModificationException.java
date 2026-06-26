package com.fintracker.ledger.budget.exception;

import java.time.LocalDate;

public class PastMonthModificationException extends RuntimeException {
    public PastMonthModificationException(LocalDate month) {
        super("Budget for past month %s is read-only and cannot be modified.".formatted(month));
    }
}
