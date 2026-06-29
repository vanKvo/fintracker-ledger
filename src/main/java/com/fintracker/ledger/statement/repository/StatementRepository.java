package com.fintracker.ledger.statement.repository;

import com.fintracker.ledger.statement.model.Statement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementRepository {

    List<Statement> findAllByUserId(UUID userId);

    Optional<Statement> findByIdAndUserId(UUID statementId, UUID userId);

    void updateStatus(UUID statementId, Statement.StatementStatus status);

    void deleteByIdAndUserId(UUID statementId, UUID userId);
}
