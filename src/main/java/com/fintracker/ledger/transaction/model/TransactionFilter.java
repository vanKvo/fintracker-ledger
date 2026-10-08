package com.fintracker.ledger.transaction.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TransactionFilter(
        UUID userId,
        UUID accountId,
        String merchantContains,
        LocalDate dateFrom,
        LocalDate dateTo,
        String category,
        List<String> tags,
        Transaction.TransactionStatus status,
        Transaction.TransactionType type,
        Transaction.TransactionDirection direction,
        int page,
        int size
) {}
