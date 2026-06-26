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
    public enum TransactionSource { STATEMENT_UPLOAD, TELLER_SYNC, MANUAL_ENTRY }
    public enum TransactionType   { SALE, RETURN }
    public enum TransactionStatus { PENDING_APPROVAL, POSTED, DELETED }
}
