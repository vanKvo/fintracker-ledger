package com.fintracker.ledger.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

public record Account(
        UUID accountId,
        UUID userId,
        String accountName,
        String accountType,
        BigDecimal currentBalance,
        String syncMode
) {}
