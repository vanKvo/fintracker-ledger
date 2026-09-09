package com.fintracker.ledger.statement.service;

import com.fintracker.ledger.statement.dto.InitiateStatementUploadRequest;
import com.fintracker.ledger.statement.dto.StatementUploadResponse;
import com.fintracker.ledger.statement.exception.DuplicateStatementException;
import com.fintracker.ledger.statement.model.Statement;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementService {

    List<Statement> getStatements(UUID userId);

    void updateStatus(UUID statementId, Statement.StatementStatus status);

    void deleteStatement(UUID statementId, UUID userId);

    /**
     * Creates a statement record (status PROCESSING) and returns a presigned
     * S3 upload URL. accountId is validated against userId before anything
     * is created — a request for another user's account is rejected the
     * same way AccountServiceImpl.updateAccount rejects one.
     */
    StatementUploadResponse initiateUpload(InitiateStatementUploadRequest request, UUID userId);

    /**
     * REQ-STMT-03/06 check, run synchronously inside initiateUpload before any S3 URL or statement row is created.
     * contentHash is always present (REQ-STMT-03 makes it required). statementMonth is nullable: initiateUpload
     * always passes the closingDate-derived month, while InternalStatementController passes null because that
     * query is only ever about content. A null statementMonth skips the SAME_MONTH branch; it is not an error.
     * Specificity priority: EXACT_FILE is evaluated first and returned if it matches; SAME_MONTH is only
     * consulted when no exact-file match exists. At most one match is ever returned.
     * Throws nothing — the caller (initiateUpload) decides whether to surface a DuplicateStatementException or proceed with an overwrite.
     */
    Optional<DuplicateCheckResult> checkForDuplicateByContentHash(
            UUID accountId, String contentHash, LocalDate statementMonth);

    record DuplicateCheckResult(
            DuplicateStatementException.MatchType matchType, UUID existingStatementId,
            OffsetDateTime existingUploadDate, int existingTransactionCount) {}
}

