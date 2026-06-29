package com.fintracker.ledger.statement.service.impl;

import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.repository.StatementRepository;
import com.fintracker.ledger.statement.service.StatementService;
import com.fintracker.ledger.statement.exception.StatementNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class StatementServiceImpl implements StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementServiceImpl.class);

    private final StatementRepository statementRepository;

    public StatementServiceImpl(StatementRepository statementRepository) {
        this.statementRepository = statementRepository;
    }

    @Override
    public List<Statement> getStatements(UUID userId) {
        return statementRepository.findAllByUserId(userId);
    }

    @Override
    public void updateStatus(UUID statementId, Statement.StatementStatus status) {
        statementRepository.updateStatus(statementId, status);
    }

    @Override
    public void deleteStatement(UUID statementId, UUID userId) {
        statementRepository.findByIdAndUserId(statementId, userId)
                .orElseThrow(() -> new StatementNotFoundException(statementId));

        statementRepository.deleteByIdAndUserId(statementId, userId);
        log.info("Hard-deleted statement statementId={}. Cascaded transactions removed.", statementId);
    }
}
