package com.fintracker.ledger.domain.service;

import com.fintracker.ledger.domain.model.Statement;
import com.fintracker.ledger.domain.ports.outbound.StatementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Domain Service: Statement Metadata Module.
 * Manages the lifecycle of statement metadata records.
 * File uploading and transaction parsing are the responsibility
 * of the external data-pipeline service.
 */
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    private final StatementRepository statementRepository;

    public StatementService(StatementRepository statementRepository) {
        this.statementRepository = statementRepository;
    }

    /** Retrieves all statement metadata records for the vault UI table. */
    public List<Statement> getStatements(UUID userId) {
        return statementRepository.findAllByUserId(userId);
    }

    /**
     * Hard-deletes a statement record.
     * The ON DELETE CASCADE constraint on ledger.transactions.statement_id
     * ensures all associated transactions are automatically removed.
     */
    public void deleteStatement(UUID statementId) {
        statementRepository.findById(statementId)
                .orElseThrow(() -> new StatementNotFoundException(statementId));

        statementRepository.deleteById(statementId);
        log.info("Hard-deleted statement statementId={}. Cascaded transactions removed.", statementId);
    }

    public static class StatementNotFoundException extends RuntimeException {
        public StatementNotFoundException(UUID id) { super("Statement not found: " + id); }
    }
}
