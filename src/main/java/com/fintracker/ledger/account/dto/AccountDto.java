package com.fintracker.ledger.account.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * REQ-3.1.D: "record the full account number in the database" — {@code accountNumber} here is
 * the complete value persisted to ledger.accounts.account_number. Masking to the last four digits
 * is a UI-only display concern (fintracker-ui's Accounts table), not something this DTO or the
 * repository that builds it should do, since the full number is also what the inline-edit form
 * needs to prefill correctly.
 */
public record AccountDto(
        UUID accountId,
        UUID userId,
        String accountName,
        String accountType,
        String accountNumber,
        String owner,
        BigDecimal currentBalance,
        String syncMode,
        OffsetDateTime createdAt
) {}
