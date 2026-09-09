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
 * <p>bankId is required when sourceFormat is CSV (the data-pipeline's column
 * mapping is keyed by bank) and must be omitted otherwise.
 *
 * <p>REQ-STMT-07: openingDate/closingDate are collected for every format, CSV
 * included — the server derives the statement's grouping month from closingDate
 * (the month a bank statement is conventionally labeled by) rather than asking
 * the user to pick a month directly, and the full range is stored and displayed.
 * The declared range is a label for organizing the statement; nothing here
 * validates it against the file's actual transaction dates.
 *
 * <p>REQ-STMT-03: contentHash is the SHA-256 of the file's exact contents,
 * computed by the client before upload. Required, not optional — an upload
 * without one is rejected outright rather than processed without the duplicate
 * check, so no client can opt itself out of duplicate detection by omitting a
 * field. overwriteStatementId is reserved for REQ-STMT-05 (overwrite an existing
 * statement); it is accepted here for forward compatibility but no overwrite
 * behavior is implemented yet.
 */
public record InitiateStatementUploadRequest(
        @NotNull UUID accountId,
        String description,
        @NotNull @Pattern(regexp = "PDF|CSV|IMAGE") String sourceFormat,
        String fileName, String bankId,
        @NotNull LocalDate openingDate, @NotNull LocalDate closingDate,   // REQ-STMT-07
        @NotNull @Pattern(regexp = "[a-f0-9]{64}") String contentHash,   // REQ-STMT-03, REQUIRED
        UUID overwriteStatementId                                       // REQ-STMT-05, optional
) {}
