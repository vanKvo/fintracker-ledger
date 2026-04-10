package com.fintracker.ledger.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single monthly budget and its categorical spending lines.
 * <p>
 * Past months ({@code effectiveMonth} before the current month) are strictly
 * read-only to preserve historical integrity.
 */
public record Budget(
        UUID budgetId,
        UUID userId,
        LocalDate effectiveMonth,
        int version,
        String description,
        List<BudgetLine> lines,
        OffsetDateTime createdAt
) {}
