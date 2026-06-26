package com.fintracker.ledger.budget.model;

import java.math.BigDecimal;
import java.util.UUID;

public record BudgetLine(
        UUID lineId,
        UUID budgetId,
        String category,
        BigDecimal limitAmount,
        String description,
        BigDecimal spentAmount
) {}
