package com.fintracker.ledger.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Request body for upserting a monthly budget. */
public record UpsertBudgetRequest(
        @NotNull LocalDate effectiveMonth,
        @NotEmpty List<@Valid BudgetLineRequest> lines
) {
    public record BudgetLineRequest(
            @NotBlank String category,
            @NotNull @DecimalMin("0.00") BigDecimal limitAmount
    ) {}
}
