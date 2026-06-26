package com.fintracker.ledger.dashboard.model;

import java.math.BigDecimal;

public record DashboardSummary(
        BigDecimal totalBalance,
        BigDecimal monthlyIncome,
        BigDecimal monthlyExpenses,
        BigDecimal cashFlow,
        BigDecimal netSaving
) {}
