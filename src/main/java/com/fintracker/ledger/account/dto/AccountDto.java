package com.fintracker.ledger.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountDto(
        UUID accountId,
        UUID userId,
        String accountName,
        String accountType,
        BigDecimal currentBalance,
        String syncMode
) {}
