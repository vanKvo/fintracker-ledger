package com.fintracker.ledger.transaction.dto;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * REQ-2.2 "Inline Row Modification". Both fields are optional so a single PATCH can update either
 * the category, the amount, or both in one round trip; the controller rejects a request with
 * neither set.
 */
public record UpdateTransactionRequest(
        @Size(max = 100) String category,
        BigDecimal amount
) {}
