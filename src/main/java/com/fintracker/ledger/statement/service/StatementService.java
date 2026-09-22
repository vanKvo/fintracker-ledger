package com.fintracker.ledger.statement.service;

import com.fintracker.ledger.statement.dto.InitiateStatementUploadRequest;
import com.fintracker.ledger.statement.dto.StatementUploadResponse;
import com.fintracker.ledger.statement.exception.DuplicateStatementException;
import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.model.StatementOwner;

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

    /**
     * REQ-STMT-04: records the aggregate fingerprint the data-pipeline computed once it had
     * read the whole file. Scoped by userId — the pipeline is trusted to write fingerprints,
     * not to nominate which tenant's statement receives one.
     *
     * @throws com.fintracker.ledger.statement.exception.StatementNotFoundException
     *         if no statement with that id belongs to that user
     */
    void recordContentFingerprint(UUID statementId, UUID userId, String contentFingerprint);

    /**
     * REQ-STMT-04 check, run by the data-pipeline mid-processing once the fingerprint is known.
     * Deliberately a separate method from checkForDuplicateByContentHash rather than an overload:
     * by this stage EXACT_FILE and SAME_MONTH have already been decided at initiateUpload time,
     * so there is no specificity ladder here — a match is always CONTENT_FINGERPRINT.
     * Throws nothing; a match is a "probably the same, please confirm" signal for the caller to
     * act on, never an error the Ledger raises on its own.
     */
    Optional<DuplicateCheckResult> checkForDuplicateByContentFingerprint(
            UUID accountId, String contentFingerprint);

    /**
     * REQ-DP-05: the Data Pipeline's S3 trigger calls this before it knows who a statement
     * belongs to — that is the whole reason it exists, replacing the old approach of trusting
     * user-id/account-id tags set on the S3 object at upload time. Not scoped by a caller
     * userId; see StatementRepository#findOwnerByStatementId.
     */
    Optional<StatementOwner> findStatementOwner(UUID statementId);

    record DuplicateCheckResult(
            DuplicateStatementException.MatchType matchType, UUID existingStatementId,
            OffsetDateTime existingUploadDate, int existingTransactionCount) {}
}

