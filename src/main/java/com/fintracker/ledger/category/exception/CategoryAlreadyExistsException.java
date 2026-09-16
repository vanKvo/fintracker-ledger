package com.fintracker.ledger.category.exception;

/**
 * REQ-TS-01 Requested Changes #5: a category name collides, post-normalization, with an
 * existing SYSTEM-level category or another of the user's own custom categories. Mapped to
 * 409 Conflict. Named distinctly from the existing, unrelated
 * {@code budget.exception.DuplicateCategoryException} (budget-line category duplication).
 */
public class CategoryAlreadyExistsException extends RuntimeException {

    public CategoryAlreadyExistsException(String categoryName) {
        super("A category named '%s' already exists.".formatted(categoryName));
    }
}
