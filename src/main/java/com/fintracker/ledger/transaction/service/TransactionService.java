package com.fintracker.ledger.transaction.service;

import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.model.TransactionFilter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TransactionService {

    List<Transaction> getTransactions(TransactionFilter filter);

    void approveTransaction(UUID transactionId, UUID userId);

    List<Transaction> splitTransaction(UUID parentId, List<SplitRequest> splits, UUID userId);

    void bulkApprove(List<UUID> transactionIds, UUID userId);

    void toggleExclude(UUID transactionId, boolean exclude, UUID userId);

    void deleteManualTransaction(UUID transactionId, UUID userId);

    BigDecimal sumMonthlyIncome(UUID userId, LocalDate start, LocalDate end);

    BigDecimal sumMonthlyExpenses(UUID userId, LocalDate start, LocalDate end);

    record SplitRequest(BigDecimal amount, String category) {}
}
