package com.fintracker.ledger.statement.repository;

import com.fintracker.ledger.statement.model.Statement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementRepository {

    List<Statement> findAllByUserId(UUID userId);

    Optional<Statement> findByIdAndUserId(UUID statementId, UUID userId);

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
