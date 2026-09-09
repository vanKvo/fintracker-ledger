package com.fintracker.ledger.statement.dto;

import java.util.UUID;

/**
 * REQ-STMT-03: 202 Accepted response for an initiated statement upload — the upload is
 * accepted for asynchronous processing, not completed.
 *
 * <p>jobId doubles as the data-pipeline job_id — the client polls
 * pipeline status at {@code GET <data-pipeline-api>/jobs/{jobId}}
 * using this same identifier once the upload completes (see
 * services/fintracker-data-pipeline's statement_ingestion/orchestrator.py).
 *
 * <p>s3ObjectKey names the exact object the presigned uploadUrl targets:
 * S3PresignService bakes the object metadata into the signature, so a client that
 * PUTs to a different key gets a signature mismatch; this field is what tells it
 * which key to use.
 */
public record StatementUploadResponse(
        UUID jobId,         // the created statement's id — the handle the client polls with
        String status,      // Statement.StatementStatus name; always "PROCESSING" at this point
        String uploadUrl,   // presigned S3 PUT URL the client uploads the file to
        String s3ObjectKey  // the key that URL targets; the client must PUT to exactly this object
) {}
