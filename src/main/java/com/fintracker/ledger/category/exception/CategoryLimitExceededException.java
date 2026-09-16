package com.fintracker.ledger.category.exception;

/** REQ-TS-01 Requested Changes #9: the user already has 50 custom categories. Mapped to
 * 400 Bad Request. */
public class CategoryLimitExceededException extends RuntimeException {

    public static final int MAX_CUSTOM_CATEGORIES = 50;

    public CategoryLimitExceededException() {
        super("A user may have at most %d custom categories.".formatted(MAX_CUSTOM_CATEGORIES));
    }
}
