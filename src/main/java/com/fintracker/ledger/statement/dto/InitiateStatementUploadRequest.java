package com.fintracker.ledger.statement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Initiates an async statement import: creates the statement record (status
 * PROCESSING) and returns a presigned S3 PUT URL for the client to upload
 * directly to. accountId is validated against the requesting user before
 * anything is created — never trusted at face value (CLAUDE.md's "never
 * trust a user-supplied ID from the request body for data scoping" applies
 * here the same as everywhere else; accountId is an exception only in the
 * sense that we still validate ownership, we just also need to know which
 * account before creating anything).
 *
 * bankId is required when sourceFormat is CSV (the data-pipeline's column
 * mapping is keyed by bank) and must be omitted otherwise. openingDate/
 * closingDate are required for PDF/IMAGE (used for soft date-range
 * validation against extracted transactions) and not applicable to CSV,
 * whose rows already carry their own dates.
 */
public record InitiateStatementUploadRequest(
        @NotNull UUID accountId,
        @NotNull LocalDate statementMonth,
        String description,
        @NotNull @Pattern(regexp = "PDF|CSV|IMAGE") String sourceFormat,
        String fileName,
        String bankId,
        LocalDate openingDate,
        LocalDate closingDate
) {}
