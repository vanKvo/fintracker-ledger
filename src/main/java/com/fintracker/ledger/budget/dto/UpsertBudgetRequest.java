package com.fintracker.ledger.budget.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REQ-5.1 payload for {@code PUT /api/v1/ledger/budgets}.
 *
 * <p>{@code lines} may be empty or absent: combined with a {@code templateId} the budget is
 * seeded from that existing budget's line items; with neither, an empty from-scratch budget is
 * created. Explicit lines always take precedence over the template.
 *
 * <p>{@code limitAmount} is validated to at most 2 decimal places ({@link Digits}) and the
 * inclusive range [0.00, 999999999.99]; violations are rejected with 400 Bad Request and never
 * silently rounded.
 */
public record UpsertBudgetRequest(
        @NotNull LocalDate effectiveMonth,
        UUID templateId,
        List<@Valid BudgetLineRequest> lines
) {
    public record BudgetLineRequest(
            @NotBlank String category,
            @NotNull
            @DecimalMin("0.00")
            @DecimalMax("999999999.99")
            @Digits(integer = 9, fraction = 2)
            BigDecimal limitAmount
    ) {}
}
