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
     * Creates a statement row with status PROCESSING, pre-generating the
     * statement_id (rather than letting the DB default assign it) so the
     * same ID can be used to build the S3 object key and presigned URL
     * before the insert happens.
     */
    Statement insert(UUID statementId, UUID accountId, String s3ObjectKey, LocalDate statementMonth,
                      String description, String sourceFormat, String bankId);
}
