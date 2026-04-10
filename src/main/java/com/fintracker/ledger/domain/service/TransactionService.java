package com.fintracker.ledger.domain.service;

import com.fintracker.ledger.domain.model.Statement;
import com.fintracker.ledger.domain.model.Transaction;
import com.fintracker.ledger.domain.ports.outbound.StatementRepository;
import com.fintracker.ledger.domain.ports.outbound.TransactionFilter;
import com.fintracker.ledger.domain.ports.outbound.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Domain Service: Transactions Module.
 * Contains only business logic. No framework annotations.
 * All dependencies are injected through constructor as Ports (interfaces).
 */
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final StatementRepository statementRepository;

    public TransactionService(TransactionRepository transactionRepository,
                              StatementRepository statementRepository) {
        this.transactionRepository = transactionRepository;
        this.statementRepository = statementRepository;
    }

    /** Fetches the ledger rows for the UI table view. */
    public List<Transaction> getTransactions(TransactionFilter filter) {
        return transactionRepository.findAll(filter);
    }

    /**
     * Approves a transaction by updating its status to POSTED.
     * After approval, checks if all sibling transactions in the statement are approved.
     * If so, marks the parent statement as COMPLETED.
     */
    public void approveTransaction(UUID transactionId) {
        var transaction = transactionRepository.findById(transactionId)
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

    /**
     * Efficient statement completion check using a COUNT query.
     * Avoids loading all transactions into memory — purely DB-side.
     */
    private void checkAndCompleteStatement(UUID statementId) {
        int pendingCount = transactionRepository.countPendingByStatementId(statementId);
        if (pendingCount == 0) {
            statementRepository.updateStatus(statementId, Statement.StatementStatus.COMPLETED);
            log.info("All transactions approved. Marked statement COMPLETED. statementId={}", statementId);
        }
    }

    /**
     * Splits a parent transaction into multiple child transactions.
     * Business Rule: The sum of all split amounts must equal the absolute value of the parent's amount.
     */
    public List<Transaction> splitTransaction(UUID parentId, List<SplitRequest> splits) {
        var parent = transactionRepository.findById(parentId)
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

    /** Performs bulk approvals on a group of transactions. */
    public void bulkApprove(List<UUID> transactionIds) {
        transactionIds.forEach(this::approveTransaction);
    }

    /** Soft-deletes a transaction from budget calculations. */
    public void toggleExclude(UUID transactionId, boolean exclude) {
        transactionRepository.toggleExcluded(transactionId, exclude);
    }

    /**
     * Hard-deletes a transaction. Strictly restricted to manual entries.
     *
     * @throws IllegalStateTransitionException if the transaction is not a manual entry
     */
    public void deleteManualTransaction(UUID transactionId) {
        var transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (!transaction.isManual()) {
            throw new IllegalStateTransitionException(
                    "Only manual transactions can be hard-deleted. transactionId=" + transactionId);
        }
        transactionRepository.deleteManualTransaction(transactionId);
    }

    private Transaction buildChildTransaction(Transaction parent, SplitRequest split) {
        return new Transaction(
                null, parent.accountId(), parent.statementId(), parent.transactionId(),
                null, split.amount(), parent.merchant(), split.category(), parent.description(),
                new ArrayList<>(), parent.txDate(), parent.source(), parent.type(),
                parent.status(), parent.isExcluded(), parent.isManual(), null
        );
    }

    public record SplitRequest(BigDecimal amount, String category) {}

    // ── Domain Exceptions ──────────────────────────────────────────────────────

    public static class TransactionNotFoundException extends RuntimeException {
        public TransactionNotFoundException(UUID id) { super("Transaction not found: " + id); }
    }

    public static class IllegalStateTransitionException extends RuntimeException {
        public IllegalStateTransitionException(String message) { super(message); }
    }

    public static class SplitAmountMismatchException extends RuntimeException {
        public SplitAmountMismatchException(BigDecimal parent, BigDecimal splitTotal) {
            super("Split total %s does not match parent transaction amount %s.".formatted(splitTotal, parent));
        }
    }
}
