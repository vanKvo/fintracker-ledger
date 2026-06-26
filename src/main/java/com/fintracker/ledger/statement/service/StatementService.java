package com.fintracker.ledger.statement.service;

import com.fintracker.ledger.statement.model.Statement;

import java.util.List;
import java.util.UUID;

public interface StatementService {

    List<Statement> getStatements(UUID userId);

    void updateStatus(UUID statementId, Statement.StatementStatus status);

    void deleteStatement(UUID statementId);
}
