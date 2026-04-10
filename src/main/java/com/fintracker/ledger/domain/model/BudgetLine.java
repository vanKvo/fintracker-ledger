package com.fintracker.ledger.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single categorical spending limit within a {@link Budget}.
 * <p>
 * {@code spentAmount} is a computed field populated at read-time by aggregating
 * POSTED transactions for the budget's {@code effectiveMonth} — not persisted.
 */
public record BudgetLine(
        UUID lineId,
        UUID budgetId,
        String category,
        BigDecimal limitAmount,
        String description,
        BigDecimal spentAmount  // Computed; not persisted in ledger.budget_lines
) {}
