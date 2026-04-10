package com.fintracker.ledger.domain.model;

import java.math.BigDecimal;

/**
 * Read-model for the Dashboard financial summary.
 * <p>
 * All values are computed on-the-fly by jOOQ aggregations using POSTED
 * transactions. This is not a persisted entity.
 *
 * @param cashFlow  Monthly income minus monthly expenses.
 * @param netSaving Cash flow after accounting for bill payments.
 */
public record DashboardSummary(
        BigDecimal totalBalance,
        BigDecimal monthlyIncome,
        BigDecimal monthlyExpenses,
        BigDecimal cashFlow,
        BigDecimal netSaving
) {}
