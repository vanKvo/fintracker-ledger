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
        // REQ-STMT-03: SHA-256 of the uploaded file's exact contents — the basis for the
        // exact-file duplicate check on the NEXT upload.
        String contentHash,
        // REQ-STMT-07: user-declared statement period, collected for every source format.
        // statementMonth above is derived from closingDate by the database (V16 generated
        // column), never supplied by a caller.
        LocalDate openingDate,
        LocalDate closingDate,
        // Derived from ledger.transactions.statement_id — 0 for a statement that has no
        // associated transactions yet (e.g. immediately after initiateUpload, before the
        // data-pipeline has ingested anything).
        int txCount,
        int pendingCount,
        int approvedCount
) {
    public enum StatementStatus { PROCESSING, COMPLETED, FAILED }
}
