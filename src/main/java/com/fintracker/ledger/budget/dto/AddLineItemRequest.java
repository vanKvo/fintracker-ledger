package com.fintracker.ledger.budget.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * REQ-5.2 payload for {@code POST /api/v1/ledger/budgets/{budgetId}/lines}.
 *
 * <p>{@code limitAmount} is validated to at most 2 decimal places ({@link Digits}) and the
 * inclusive range [0.00, 999999999.99] at the HTTP boundary; {@link BudgetLineService} enforces
 * the same rule again for callers that bypass the controller.
 */
public record AddLineItemRequest(
        @NotBlank String category,
        @NotNull
        @DecimalMin("0.00")
        @DecimalMax("999999999.99")
        @Digits(integer = 9, fraction = 2)
        BigDecimal limitAmount
) {}
