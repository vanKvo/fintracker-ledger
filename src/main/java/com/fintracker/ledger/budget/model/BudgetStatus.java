package com.fintracker.ledger.budget.model;

/**
 * REQ-5.1 "State Initialization" / "Modification Guard": a budget is either open for writes
 * (ACTIVE) or frozen for historical audit integrity (CLOSED). Persisted as a string in
 * {@code ledger.budgets.status}, constrained by a CHECK to exactly these two values.
 */
public enum BudgetStatus {
    ACTIVE,
    CLOSED
}
