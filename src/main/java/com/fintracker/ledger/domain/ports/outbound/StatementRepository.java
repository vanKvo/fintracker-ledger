package com.fintracker.ledger.domain.ports.outbound;

import com.fintracker.ledger.domain.model.Statement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for Statement metadata persistence.
 * File parsing is owned by the data-pipeline service; the Ledger Service
 * only manages the lifecycle status of statement metadata.
 */
public interface StatementRepository {

    List<Statement> findAllByUserId(UUID userId);

    Optional<Statement> findById(UUID statementId);

    void updateStatus(UUID statementId, Statement.StatementStatus status);

    void deleteById(UUID statementId);
}
