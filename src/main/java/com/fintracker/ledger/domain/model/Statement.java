package com.fintracker.ledger.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable domain model for bank statement metadata.
 * <p>
 * Statement file parsing is handled by the external data-pipeline service.
 * The Ledger Service manages the lifecycle status of this metadata record,
 * transitioning it to {@code COMPLETED} once all linked transactions are approved.
 */
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
