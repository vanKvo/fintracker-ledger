package com.fintracker.ledger.account.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * REQ-3.2 "Create New Account". {@code accountNumber}/{@code owner} are optional at the DTO level
 * since the spec doesn't mark them required; both are persisted as-is to ledger.accounts (see
 * V5__Add_Account_Number_And_Owner.sql) — {@code accountNumber} is stored in full, with masking
 * to the last four digits left to the UI (see AccountDto's Javadoc).
 */
public record CreateAccountRequest(
        @NotBlank String accountName,
        @NotBlank String accountType,
        String accountNumber,
        String owner,
        String syncMode // "MANUAL" (default) or "AUTOMATED" — see REQ-3.1.D
) {}
