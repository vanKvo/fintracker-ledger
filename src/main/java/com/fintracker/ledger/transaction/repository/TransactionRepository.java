package com.fintracker.ledger.transaction.repository;

import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.model.TransactionFilter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository {

    List<Transaction> findAll(TransactionFilter filter);

    Optional<Transaction> findByIdAndUserId(UUID transactionId, UUID userId);

    Transaction save(Transaction transaction);

    List<Transaction> saveAll(List<Transaction> transactions);

    void updateStatus(UUID transactionId, Transaction.TransactionStatus newStatus);

    void updateCategory(UUID transactionId, String category);

    void updateAmount(UUID transactionId, BigDecimal amount);

    void appendTags(UUID transactionId, List<String> newTags);

    void toggleExcluded(UUID transactionId, boolean isExcluded);

    void deleteManualTransaction(UUID transactionId);

    int countPendingByStatementId(UUID statementId);

    BigDecimal sumMonthlyIncome(UUID userId, LocalDate monthStart, LocalDate monthEnd);

    BigDecimal sumMonthlyExpenses(UUID userId, LocalDate monthStart, LocalDate monthEnd);

    BigDecimal sumMonthlyExpensesPerCategory(UUID userId, LocalDate monthStart, LocalDate monthEnd, String category);

    /**
     * Approved expense totals for a whole date span, pre-grouped by calendar month and category —
     * the batched form of {@link #sumMonthlyExpensesPerCategory}.
     *
     * <p>Exists so a caller enriching many budgets at once (REQ-5.1 A.2 "Get Budgets" spans up to
     * twelve budgets of up to 50 lines each) issues a single aggregate instead of one query per
     * line. The per-category variant remains the right call for a single month.
     *
     * @param rangeStart first day of the span, inclusive.
     * @param rangeEnd   last day of the span, inclusive.
     * @return month-start date → lower-cased category → summed amount. Months and categories with
     *         no approved spending are simply absent; the map is never null.
     */
    Map<LocalDate, Map<String, BigDecimal>> sumExpensesByMonthAndCategory(
            UUID userId, LocalDate rangeStart, LocalDate rangeEnd);
}
