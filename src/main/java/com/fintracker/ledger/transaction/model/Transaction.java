package com.fintracker.ledger.transaction.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Transaction(
        UUID transactionId,
        UUID accountId,
        UUID statementId,
        UUID parentTransactionId,
        String externalTxId,
        BigDecimal amount,
        String merchant,
        String category,
        String description,
        List<String> tags,
        LocalDate txDate,
        TransactionSource source,
        TransactionType type,
        TransactionStatus status,
        boolean isExcluded,
        boolean isManual,
        OffsetDateTime createdAt,
        // REQ-STMT-02: idempotency key for statement uploads — a hash of the row's own
        // date/merchant/amount, computed by the data-pipeline. Null for manual/bank-sync
        // rows; the partial unique index idx_unique_statement_row_fingerprint (V12)
        // ignores nulls.
        String rowFingerprint,
        // TXT-01: money out (DEBIT) or in (CREDIT), independent of type.
        TransactionDirection direction,
        // ISO 4217 code; DEFAULT_CURRENCY when the caller supplies none.
        String currency,
        Boolean isRecurring,
        // TXT-01: e.g. a refund's original expense. Not set at ingestion.
        UUID linkedTransactionId
) {
    public static final String DEFAULT_CURRENCY = "USD";

    public enum TransactionSource    { STATEMENT_UPLOAD, BANK_SYNC, MANUAL_ENTRY }
    public enum TransactionType      { EXPENSE, INCOME, REFUND, TRANSFER, ADJUSTMENT }
    public enum TransactionDirection { DEBIT, CREDIT }
    public enum TransactionStatus    { PENDING, POSTED, DELETED }
}
