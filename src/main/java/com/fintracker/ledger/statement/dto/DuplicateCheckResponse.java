package com.fintracker.ledger.statement.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * REQ-STMT-03: answer to the data-pipeline Gatekeeper's internal duplicate check
 * ({@code GET /api/v1/ledger/statements/internal/duplicate-check}). When
 * {@code duplicateFound} is false the remaining fields are null; when true they carry
 * the same details the user-facing 409 carries, so the pipeline can record exactly why
 * processing stopped.
 */
public record DuplicateCheckResponse(
        boolean duplicateFound, String matchType, UUID existingStatementId,
        OffsetDateTime existingUploadDate, Integer existingTransactionCount
) {}
