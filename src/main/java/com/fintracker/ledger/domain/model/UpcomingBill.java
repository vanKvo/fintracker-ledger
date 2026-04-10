package com.fintracker.ledger.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A recurring scheduled expense for the Dashboard Upcoming Bills widget. */
public record UpcomingBill(
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
