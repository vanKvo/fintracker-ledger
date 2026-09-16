package com.fintracker.ledger.category.exception;

import java.util.UUID;

/** REQ-TS-01 Requested Changes #2: an attempt to update or delete a SYSTEM-level category.
 * Mapped to 400 Bad Request — system categories are read-only. */
public class SystemCategoryImmutableException extends RuntimeException {

    public SystemCategoryImmutableException(UUID categoryId) {
        super("Category %s is a system-level category and cannot be modified or deleted.".formatted(categoryId));
    }
}
