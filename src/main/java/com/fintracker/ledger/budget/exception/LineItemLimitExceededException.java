package com.fintracker.ledger.budget.exception;

/**
 * REQ-5.1 Constraints: "Maximum active budget line items per budget is 50."
 * Maps to 400 Bad Request.
 */
public class LineItemLimitExceededException extends RuntimeException {

    public static final int MAX_LINE_ITEMS = 50;

    public LineItemLimitExceededException(int attempted) {
        super("A budget may hold at most %d line items; got %d.".formatted(MAX_LINE_ITEMS, attempted));
    }
}
