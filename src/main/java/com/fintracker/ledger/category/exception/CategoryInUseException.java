package com.fintracker.ledger.category.exception;

import java.util.UUID;

/**
 * REQ-TS-01 Requested Changes #6: a delete was attempted on a category with referencing
 * transactions and no reassignment target. Carries the referencing count so the client can
 * present a reassignment prompt. Mapped to 409 Conflict.
 */
public class CategoryInUseException extends RuntimeException {

    private final long transactionCount;

    public CategoryInUseException(UUID categoryId, long transactionCount) {
        super("Category %s is referenced by %d transaction(s) and requires a reassignment target before it can be deleted."
                .formatted(categoryId, transactionCount));
        this.transactionCount = transactionCount;
    }

    public long getTransactionCount() {
        return transactionCount;
    }
}
