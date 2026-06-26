package com.fintracker.ledger.bill.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record UpcomingBillDto(
        UUID billId,
        UUID userId,
        String name,
        BigDecimal amount,
        int dueDateDay,
        String category,
        String description,
        BillStatus status,
        OffsetDateTime createdAt
) {
    public enum BillStatus { ACTIVE, PAUSED }
}
