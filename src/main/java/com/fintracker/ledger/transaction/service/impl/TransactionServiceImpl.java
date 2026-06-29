package com.fintracker.ledger.transaction.service.impl;

import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.service.StatementService;
import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.model.TransactionFilter;
import com.fintracker.ledger.transaction.repository.TransactionRepository;
import com.fintracker.ledger.transaction.service.TransactionService;
import com.fintracker.ledger.transaction.exception.TransactionNotFoundException;
import com.fintracker.ledger.transaction.exception.IllegalStateTransitionException;
import com.fintracker.ledger.transaction.exception.SplitAmountMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionServiceImpl implements TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionServiceImpl.class);

    private final TransactionRepository transactionRepository;
    private final StatementService statementService;

    public TransactionServiceImpl(TransactionRepository transactionRepository,
                                  StatementService statementService) {
        this.transactionRepository = transactionRepository;
        this.statementService = statementService;
    }

    @Override
    public List<Transaction> getTransactions(TransactionFilter filter) {
        return transactionRepository.findAll(filter);
    }

    @Override
    public void approveTransaction(UUID transactionId, UUID userId) {
        var transaction = transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (transaction.status() != Transaction.TransactionStatus.PENDING_APPROVAL) {
            throw new IllegalStateTransitionException(
                    "Transaction %s is not in PENDING_APPROVAL state.".formatted(transactionId));
        }

        transactionRepository.updateStatus(transactionId, Transaction.TransactionStatus.POSTED);
        log.info("Approved transaction transactionId={}", transactionId);

        if (transaction.statementId() != null) {
            checkAndCompleteStatement(transaction.statementId());
        }
    }

    private void checkAndCompleteStatement(UUID statementId) {
        int pendingCount = transactionRepository.countPendingByStatementId(statementId);
        if (pendingCount == 0) {
            statementService.updateStatus(statementId, Statement.StatementStatus.COMPLETED);
            log.info("All transactions approved. Marked statement COMPLETED. statementId={}", statementId);
        }
    }

    @Override
    public List<Transaction> splitTransaction(UUID parentId, List<SplitRequest> splits, UUID userId) {
        var parent = transactionRepository.findByIdAndUserId(parentId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(parentId));

        if (parent.status() == Transaction.TransactionStatus.POSTED) {
            throw new IllegalStateTransitionException(
                    "Posted transactions cannot be split. transactionId=" + parentId);
        }

        BigDecimal totalSplit = splits.stream()
                .map(SplitRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (parent.amount().abs().compareTo(totalSplit.abs()) != 0) {
            throw new SplitAmountMismatchException(parent.amount(), totalSplit);
        }

        List<Transaction> children = splits.stream()
                .map(split -> buildChildTransaction(parent, split))
                .toList();

        return transactionRepository.saveAll(children);
    }

    @Override
    public void bulkApprove(List<UUID> transactionIds, UUID userId) {
        transactionIds.forEach(id -> approveTransaction(id, userId));
    }

    @Override
    public void toggleExclude(UUID transactionId, boolean exclude, UUID userId) {
        transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        transactionRepository.toggleExcluded(transactionId, exclude);
    }

    @Override
    public void deleteManualTransaction(UUID transactionId, UUID userId) {
        var transaction = transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (!transaction.isManual()) {
            throw new IllegalStateTransitionException(
                    "Only manual transactions can be hard-deleted. transactionId=" + transactionId);
        }
        transactionRepository.deleteManualTransaction(transactionId);
    }

    @Override
    public BigDecimal sumMonthlyIncome(UUID userId, LocalDate start, LocalDate end) {
        return transactionRepository.sumMonthlyIncome(userId, start, end);
    }

    @Override
    public BigDecimal sumMonthlyExpenses(UUID userId, LocalDate start, LocalDate end) {
        return transactionRepository.sumMonthlyExpenses(userId, start, end);
    }

    private Transaction buildChildTransaction(Transaction parent, SplitRequest split) {
        return new Transaction(
                null, parent.accountId(), parent.statementId(), parent.transactionId(),
                null, split.amount(), parent.merchant(), split.category(), parent.description(),
                new ArrayList<>(), parent.txDate(), parent.source(), parent.type(),
                parent.status(), parent.isExcluded(), parent.isManual(), null
        );
    }
}
