package com.fintracker.ledger.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ManualTransactionRequest(
        @NotNull UUID accountId,
        @NotNull @DecimalMin(value = "0.01", message = "Amount must be non-zero") BigDecimal amount,
        @NotBlank String merchant,
        @NotBlank String category,
        List<String> tags,
        @NotNull LocalDate txDate,
        @NotNull String type  // SALE or RETURN
) {}
