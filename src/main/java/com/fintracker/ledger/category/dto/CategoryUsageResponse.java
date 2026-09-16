package com.fintracker.ledger.category.dto;

/** REQ-TS-01 Requested Changes #6 — lets the client decide up front whether a reassignment
 * prompt is needed before attempting a delete. */
public record CategoryUsageResponse(long transactionCount) {}
