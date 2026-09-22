package com.fintracker.ledger.statement.repository;

import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.model.StatementOwner;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementRepository {

    List<Statement> findAllByUserId(UUID userId);

    Optional<Statement> findByIdAndUserId(UUID statementId, UUID userId);

    /**
     * REQ-DP-05: the statement's actual owner, looked up by statement_id alone. Unlike every
     * other read on this interface, this is deliberately NOT scoped by a caller-supplied
     * userId — the Data Pipeline calls this specifically because it does not yet know who the
     * real owner is (it previously trusted S3 object metadata tags for that instead).
     */
    Optional<StatementOwner> findOwnerByStatementId(UUID statementId);

    void updateStatus(UUID statementId, Statement.StatementStatus status);

    void deleteByIdAndUserId(UUID statementId, UUID userId);

    /**
     * REQ-STMT-03 duplicate-check query: the account's prior upload with the exact same
     * file contents, if any. Scoped to one account — the same file uploaded to the WRONG
     * account is a different mistake and must not be confused with a true duplicate.
     */
    Optional<Statement> findByAccountIdAndContentHash(UUID accountId, String contentHash);

    /**
     * REQ-STMT-06 duplicate-check query: the statement already covering the account's
     * given (month-start) statement month, if any. Backed at the DB level by
     * idx_unique_account_statement_month (V1, recreated by V16), so at most one row can
     * ever match.
     */
    Optional<Statement> findByAccountIdAndStatementMonth(UUID accountId, LocalDate statementMonth);

    /**
     * REQ-STMT-04 duplicate-check query: the account's prior statement whose whole
     * transaction set produced the same aggregate fingerprint. Account-scoped for the same
     * reason the content-hash lookup is — a match in someone else's account is not this
     * user's duplicate, and reporting one would disclose that the other account exists.
     *
     * <p>Unlike the content-hash index, the backing index is NOT unique: REQ-STMT-04 treats a
     * fingerprint match as "probably the same, please confirm", so more than one row can
     * legitimately carry the same value. Returns the first match; the caller only needs to
     * know that one exists and which statement to show the user.
     */
    Optional<Statement> findByAccountIdAndContentFingerprint(UUID accountId, String contentFingerprint);

    /**
     * REQ-STMT-04: records the aggregate fingerprint once the data-pipeline has read the
     * file. Scoped by userId as well as statementId so an internal caller cannot stamp a
     * fingerprint onto another tenant's statement — proving the caller is the pipeline does
     * not authorize it to write to an arbitrary account.
     *
     * @return true if a row was updated, false if no statement matched that id for that user
     */
    boolean updateContentFingerprint(UUID statementId, UUID userId, String contentFingerprint);

    /**
     * Creates a statement row with status PROCESSING, pre-generating the
     * statement_id (rather than letting the DB default assign it) so the
     * same ID can be used to build the S3 object key and presigned URL
     * before the insert happens.
     *
     * <p>statement_month is deliberately NOT a parameter: since V16 the database derives
     * it from closing_date as a generated column, so the two can never drift apart.
     */
    Statement insert(UUID statementId, UUID accountId, String s3ObjectKey,
                      LocalDate openingDate, LocalDate closingDate, String contentHash,
                      String description, String sourceFormat, String bankId);
}
