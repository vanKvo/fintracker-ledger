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
        OffsetDateTime createdAt
) {
    public enum TransactionSource { STATEMENT_UPLOAD, BANK_SYNC, MANUAL_ENTRY }
    public enum TransactionType   { PURCHASE, CREDIT }
    public enum TransactionStatus { PENDING, POSTED, DELETED }
}
