package com.fintracker.ledger.transaction.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record BulkActionRequest(
        @NotEmpty List<@NotNull UUID> transactionIds,
        @NotNull BulkActionType action
) {
    public enum BulkActionType { APPROVE, EXCLUDE, INCLUDE }
}
