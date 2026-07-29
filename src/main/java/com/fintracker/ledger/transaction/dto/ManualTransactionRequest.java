package com.fintracker.ledger.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REQ-2.3.1 "Manual Row Insertion". txDate is intentionally NOT {@code @NotNull} — per the spec's
 * Business Constraints, it defaults to today when omitted; TransactionServiceImpl applies that
 * default rather than rejecting a request that leaves it blank.
 *
 * <p>amount is deliberately NOT {@code @DecimalMin} — transactions in this system use negative
 * amounts for expenses (PURCHASE) and positive amounts for income (CREDIT), e.g. a $50 grocery
 * purchase is stored as -50.00; a floor of 0.01 would reject every expense entry. The
 * non-zero check (matching the ledger.transactions {@code CHECK (amount != 0)} constraint and
 * the same rule updateAmount() already enforces) happens in
 * TransactionServiceImpl.createManualTransaction instead.
 */
public record ManualTransactionRequest(
        @NotNull UUID accountId,
        @NotNull BigDecimal amount,
        @NotBlank String merchant,
        @NotBlank String category,
        List<String> tags,
        LocalDate txDate,
        @NotNull String type  // PURCHASE or CREDIT
) {}
