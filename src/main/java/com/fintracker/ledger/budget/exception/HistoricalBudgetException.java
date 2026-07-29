package com.fintracker.ledger.budget.exception;

import java.util.UUID;

/**
 * REQ-5.1 "Immutability upon Closure": any write attempted against a budget whose status is
 * CLOSED. Maps to 422 Unprocessable Entity — the request is well-formed, but the target's
 * lifecycle state forbids it.
 */
public class HistoricalBudgetException extends RuntimeException {

    public HistoricalBudgetException(UUID budgetId) {
        super("Budget %s is CLOSED and cannot be modified. Reopen it first.".formatted(budgetId));
    }

    public HistoricalBudgetException(String message) {
        super(message);
    }
}
