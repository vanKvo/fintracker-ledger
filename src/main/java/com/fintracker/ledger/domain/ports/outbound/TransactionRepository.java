package com.fintracker.ledger.domain.ports.outbound;

import com.fintracker.ledger.domain.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port (persistence interface) for the Transaction aggregate.
 * Domain layer defines this interface; the infrastructure jOOQ adapter implements it.
 */
public interface TransactionRepository {

    List<Transaction> findAll(TransactionFilter filter);

    Optional<Transaction> findById(UUID transactionId);

    Transaction save(Transaction transaction);

    List<Transaction> saveAll(List<Transaction> transactions);

    void updateStatus(UUID transactionId, Transaction.TransactionStatus newStatus);

    void updateCategory(UUID transactionId, String category);

    void appendTags(UUID transactionId, List<String> newTags);

    void toggleExcluded(UUID transactionId, boolean isExcluded);

    void deleteManualTransaction(UUID transactionId);

    /**
     * Counts remaining PENDING_APPROVAL transactions for a given statement.
     * Used to determine if a statement processing cycle is complete.
     * Uses idx_tx_statement_status index — O(log n).
     */
    int countPendingByStatementId(UUID statementId);

    BigDecimal sumMonthlyIncome(UUID userId, LocalDate monthStart, LocalDate monthEnd);

    BigDecimal sumMonthlyExpenses(UUID userId, LocalDate monthStart, LocalDate monthEnd);
}
