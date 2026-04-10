package com.fintracker.ledger.api.dto;

import java.util.UUID;

/** Request body for marking an upcoming bill as paid. */
public record MarkBillPaidRequest(
        UUID transactionId // Optional: links to an existing ledger transaction
) {}
