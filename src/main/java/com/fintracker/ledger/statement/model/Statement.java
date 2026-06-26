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
        OffsetDateTime uploadDate
) {
    public enum StatementStatus { PROCESSING, COMPLETED, FAILED }
}
