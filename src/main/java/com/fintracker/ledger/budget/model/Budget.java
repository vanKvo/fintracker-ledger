package com.fintracker.ledger.budget.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Budget(
        UUID budgetId,
        UUID userId,
        LocalDate effectiveMonth,
        int version,
        BudgetStatus status,
        String description,
        List<BudgetLine> lines,
        OffsetDateTime createdAt
) {}
