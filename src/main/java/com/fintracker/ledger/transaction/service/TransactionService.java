package com.fintracker.ledger.transaction.service;

import com.fintracker.ledger.transaction.dto.ManualTransactionRequest;
import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.model.TransactionFilter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TransactionService {

    List<Transaction> getTransactions(TransactionFilter filter);

    /**
     * REQ-2.3.1 "Manual Row Insertion". NOT YET IMPLEMENTED — see
     * TransactionServiceImpl.createManualTransaction and TransactionServiceTest's
     * CreateManualTransaction nested class for the FAIL-TO-PASS tests describing intended
     * behavior (source=MANUAL_ENTRY, isManual=true, status=POSTED, txDate defaults to today).
     */
    Transaction createManualTransaction(ManualTransactionRequest request, UUID userId);

    void approveTransaction(UUID transactionId, UUID userId);

    List<Transaction> splitTransaction(UUID parentId, List<SplitRequest> splits, UUID userId);

    void bulkApprove(List<UUID> transactionIds, UUID userId);

    void toggleExclude(UUID transactionId, boolean exclude, UUID userId);

    /**
     * REQ-2.2 "Inline Row Modification" (category).
     */
    void updateCategory(UUID transactionId, String category, UUID userId);

    /**
     * REQ-2.2 "Inline Row Modification" (amount). Rejects a zero amount to fail fast ahead of the
     * DB CHECK (amount != 0) constraint on ledger.transactions.
     */
    void updateAmount(UUID transactionId, BigDecimal amount, UUID userId);

    /**
     * REQ-2.2 "Tag Array Appending".
     */
    void appendTags(UUID transactionId, List<String> newTags, UUID userId);

    void deleteManualTransaction(UUID transactionId, UUID userId);

    BigDecimal sumMonthlyIncome(UUID userId, LocalDate start, LocalDate end);

    BigDecimal sumMonthlyExpenses(UUID userId, LocalDate start, LocalDate end);

    /**
     * REQ-5.1 "Spend Amount Initialization". Sums approved expenses — POSTED status, PURCHASE
     * type, not excluded, not a split parent — for a single category within [start, end].
     * Category matching is case-insensitive. Returns {@link BigDecimal#ZERO} (never null) when
     * nothing matches.
     */
    BigDecimal sumMonthlyExpensesPerCategory(UUID userId, LocalDate start, LocalDate end, String category);

    record SplitRequest(BigDecimal amount, String category) {}
}
