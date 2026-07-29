package com.fintracker.ledger.budget.exception;

/**
 * REQ-5.1 Constraints: a payload that violates the limitAmount range [0.00, 999999999.99], the
 * 2-decimal scale rule, or category uniqueness/blankness. Maps to 400 Bad Request.
 */
public class InvalidBudgetException extends RuntimeException {

    public InvalidBudgetException(String message) {
        super(message);
    }
}
