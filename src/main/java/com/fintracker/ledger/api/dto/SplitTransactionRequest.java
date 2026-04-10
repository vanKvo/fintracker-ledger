package com.fintracker.ledger.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/** Request body for splitting a transaction into multiple child transactions. */
public record SplitTransactionRequest(
        @NotEmpty List<@Valid SplitItem> splits
) {
    public record SplitItem(
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @NotBlank String category
    ) {}
}
