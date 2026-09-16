package com.fintracker.ledger.category.exception;

import java.util.UUID;

/**
 * REQ-TS-01 Requested Changes #2: a categoryId that does not exist, or that exists but is not
 * accessible to the caller (another user's custom category). Both cases answer identically —
 * 404 Not Found — so existence is never leaked across tenants, matching this codebase's
 * existing convention (see StatementNotFoundException).
 */
public class CategoryNotFoundException extends RuntimeException {

    public CategoryNotFoundException(UUID categoryId) {
        super("Category %s was not found.".formatted(categoryId));
    }
}
