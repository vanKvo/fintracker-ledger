package com.fintracker.ledger.statement.dto;

import java.util.UUID;

/**
 * statementId doubles as the data-pipeline job_id — the client polls
 * pipeline status at {@code GET <data-pipeline-api>/jobs/{statementId}}
 * using this same identifier once the upload completes (see
 * services/fintracker-data-pipeline's statement_ingestion/orchestrator.py).
 */
public record StatementUploadResponse(
        UUID statementId,
        String presignedUploadUrl,
        String s3ObjectKey
) {}
