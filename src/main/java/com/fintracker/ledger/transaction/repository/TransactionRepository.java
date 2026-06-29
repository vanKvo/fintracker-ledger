package com.fintracker.ledger.transaction.repository;

import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.model.TransactionFilter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository {

    List<Transaction> findAll(TransactionFilter filter);

    Optional<Transaction> findByIdAndUserId(UUID transactionId, UUID userId);

    Transaction save(Transaction transaction);

    List<Transaction> saveAll(List<Transaction> transactions);

    void updateStatus(UUID transactionId, Transaction.TransactionStatus newStatus);

    void updateCategory(UUID transactionId, String category);

    void appendTags(UUID transactionId, List<String> newTags);

    void toggleExcluded(UUID transactionId, boolean isExcluded);

    void deleteManualTransaction(UUID transactionId);

    int countPendingByStatementId(UUID statementId);

    BigDecimal sumMonthlyIncome(UUID userId, LocalDate monthStart, LocalDate monthEnd);

    BigDecimal sumMonthlyExpenses(UUID userId, LocalDate monthStart, LocalDate monthEnd);
}
