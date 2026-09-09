package com.fintracker.ledger.transaction.dto;

import java.util.List;

/**
 * REQ-STMT-02: outcome of one bulk statement import. Always 200 OK once the statement
 * ownership check passes — genuinely malformed rows are reported back per-row rather
 * than aborting the batch, and retried duplicates are counted, not errors.
 *
 * @param insertedCount         rows actually written by the multi-row insert.
 * @param skippedDuplicateCount accepted rows already recorded under the same
 *                              (statement_id, row_fingerprint) — a retried batch is a
 *                              safe no-op for these.
 * @param failedRows            rows rejected by application-level validation before the
 *                              insert was built, with their index in the request's
 *                              transactions list.
 */
public record BulkCreateTransactionsResponse(
        int insertedCount,
        int skippedDuplicateCount,
        List<FailedRow> failedRows
) {
    public record FailedRow(int index, String reason) {}
}
