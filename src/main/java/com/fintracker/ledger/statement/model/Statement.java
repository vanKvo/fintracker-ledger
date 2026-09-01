package com.fintracker.ledger.statement.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Statement(
        UUID statementId,
        UUID accountId,
        String s3ObjectKey,
        LocalDate statementMonth,
        StatementStatus status,
        String description,
        OffsetDateTime uploadDate,
        String sourceFormat,
        String bankId,
        // Derived from ledger.transactions.statement_id — 0 for a statement that has no
        // associated transactions yet (e.g. immediately after initiateUpload, before the
        // data-pipeline has ingested anything).
        int txCount,
        int pendingCount,
        int approvedCount
) {
    public enum StatementStatus { PROCESSING, COMPLETED, FAILED }
}
