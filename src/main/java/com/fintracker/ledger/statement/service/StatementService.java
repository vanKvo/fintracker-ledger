package com.fintracker.ledger.statement.service;

import com.fintracker.ledger.statement.dto.InitiateStatementUploadRequest;
import com.fintracker.ledger.statement.dto.StatementUploadResponse;
import com.fintracker.ledger.statement.model.Statement;

import java.util.List;
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
}
