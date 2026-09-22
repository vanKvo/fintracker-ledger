package com.fintracker.ledger.statement.dto;

import java.util.UUID;

/**
 * REQ-DP-05: response for GET /api/v1/ledger/statements/internal/{id}/owner — the Data
 * Pipeline's source of truth for who a statement belongs to, replacing the old approach of
 * trusting user-id/account-id tags set on the S3 object at upload time.
 */
public record StatementOwnerResponse(UUID statementId, UUID accountId, UUID userId) {}
