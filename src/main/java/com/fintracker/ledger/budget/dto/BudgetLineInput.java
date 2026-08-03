package com.fintracker.ledger.budget.dto;

import java.math.BigDecimal;

/**
 * REQ-5.2 E. Interface Details: the service-layer input to
 * {@code BudgetLineService#addLineItem} — a category and a limit, nothing persistence-specific.
 */
public record BudgetLineInput(String category, BigDecimal limitAmount) {}
