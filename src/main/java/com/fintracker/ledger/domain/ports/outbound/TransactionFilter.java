package com.fintracker.ledger.domain.ports.outbound;

import com.fintracker.ledger.domain.model.Transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Immutable value object encapsulating all filter criteria for the Transaction query.
 * All fields are optional; absent fields are ignored in the underlying SQL query.
 */
public record TransactionFilter(
        UUID userId,
        UUID accountId,
        String merchantContains,
        LocalDate dateFrom,
        LocalDate dateTo,
        String category,
        List<String> tags,
        Transaction.TransactionStatus status,
        int page,
        int size
) {}
