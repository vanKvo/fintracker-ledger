package com.fintracker.ledger.transaction.service;

import com.fintracker.ledger.transaction.dto.BulkCreateTransactionsRequest;
import com.fintracker.ledger.transaction.dto.BulkCreateTransactionsResponse;
import com.fintracker.ledger.transaction.dto.ManualTransactionRequest;
import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.model.TransactionFilter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface TransactionService {

    List<Transaction> getTransactions(TransactionFilter filter);

    /**
     * REQ-STMT-02. Validates statementId belongs to userId, converts each accepted
     * TransactionLine into a Transaction (source=STATEMENT_UPLOAD, status=PENDING),
     * and delegates to TransactionRepository.bulkInsertIgnoringDuplicates. Rows that
     * fail basic validation (blank merchant, etc.) are excluded
     * from the insert and reported back as failedRows rather than aborting the batch.
     */
    BulkCreateTransactionsResponse bulkCreateFromStatement(
            UUID statementId, UUID userId, List<BulkCreateTransactionsRequest.TransactionLine> lines);

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

    /**
     * REQ-5.1 A.2 "Spend Enrichment". The batched form of
     * {@link #sumMonthlyExpensesPerCategory}: approved expenses across [start, end], pre-grouped
     * by calendar month and lower-cased category, in a single aggregate.
     *
     * <p>Enriching a year of budgets one line at a time would issue up to 600 queries (12 months
     * x 50 lines). This keeps the cost of a year listing at one query regardless of how many
     * budgets or lines it spans.
     *
     * @return month-start date → lower-cased category → summed amount; absent entries mean no
     *         approved spending, never null.
     */
    Map<LocalDate, Map<String, BigDecimal>> sumExpensesByMonthAndCategory(
            UUID userId, LocalDate start, LocalDate end);

    record SplitRequest(BigDecimal amount, String category) {}
}
