package com.fintracker.ledger.budget.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** REQ-5.2 payload for {@code PUT /api/v1/ledger/budgets/{budgetId}/lines/{lineId}}. */
public record UpdateLineItemLimitRequest(
        @NotNull
        @DecimalMin("0.00")
        @DecimalMax("999999999.99")
        @Digits(integer = 9, fraction = 2)
        BigDecimal limitAmount
) {}
