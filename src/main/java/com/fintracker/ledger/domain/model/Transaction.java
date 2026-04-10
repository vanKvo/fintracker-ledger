package com.fintracker.ledger.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Immutable domain model representing a single financial ledger entry.
 * <p>
 * The {@code isManual} flag is derived: a transaction is manual if and only if
 * its {@code source} is {@code MANUAL_ENTRY}.
 * The {@code isExcluded} flag hides transactions from budget calculations
 * without hard-deleting immutable synced records.
 *
 * @param parentTransactionId Present only for split-transaction children.
 * @param statementId         Links to the originating uploaded statement; null for manual entries.
 */
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
