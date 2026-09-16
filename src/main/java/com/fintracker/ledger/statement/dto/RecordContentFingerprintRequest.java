package com.fintracker.ledger.statement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * REQ-STMT-04: the aggregate fingerprint of a statement's whole transaction set, sent by the
 * data-pipeline once it has read the file.
 *
 * <p>Shape-validated here the same way REQ-STMT-03's contentHash is on the upload request: a
 * value that is not 64 lowercase hex characters is not a SHA-256 and can only ever be a caller
 * bug, so it is rejected at the edge rather than stored and silently failing to match anything
 * later.
 */
public record RecordContentFingerprintRequest(
        @NotNull @Pattern(regexp = "[a-f0-9]{64}") String contentFingerprint) {}
