package com.fintracker.ledger.budget.exception;

/**
 * REQ-5.2 "Category Uniqueness": a new line item's category (case-insensitive) already exists on
 * the target budget. Maps to 409 Conflict.
 */
public class DuplicateCategoryException extends RuntimeException {

    public DuplicateCategoryException(String category) {
        super("Category '%s' already exists on this budget.".formatted(category));
    }
}
