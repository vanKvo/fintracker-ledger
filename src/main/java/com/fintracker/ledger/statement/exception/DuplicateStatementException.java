package com.fintracker.ledger.statement.exception;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * REQ-STMT-03/06 (and indirectly REQ-STMT-05): an upload was recognized as duplicating
 * a statement already on record for the same account. Carries everything the 409
 * response needs so the user can decide whether to overwrite or cancel: what kind of
 * match it was, which existing statement it matched, when that statement was uploaded,
 * and how many transactions came from it.
 *
 * <p>Mapped by {@code GlobalExceptionHandler.handleDuplicateStatement} to
 * {@code 409 Conflict} with the fields below exposed as ProblemDetail properties.
 */
public class DuplicateStatementException extends RuntimeException {

    /**
     * Specificity priority: EXACT_FILE (the identical file) is a more precise statement
     * of what happened than SAME_MONTH (some statement already covers this month), so
     * when both would match, EXACT_FILE is reported. CONTENT_FINGERPRINT is reserved
     * for REQ-STMT-04's aggregate-content match.
     */
    public enum MatchType { EXACT_FILE, CONTENT_FINGERPRINT, SAME_MONTH }

    private final MatchType matchType;
    private final UUID existingStatementId;
    private final OffsetDateTime existingUploadDate;
    private final int existingTransactionCount;

    public DuplicateStatementException(MatchType matchType, UUID existingStatementId,
                                       OffsetDateTime existingUploadDate, int existingTransactionCount) {
        super("A statement already exists for this account "
                + "(matchType=%s, existingStatementId=%s).".formatted(matchType, existingStatementId));
        this.matchType = matchType;
        this.existingStatementId = existingStatementId;
        this.existingUploadDate = existingUploadDate;
        this.existingTransactionCount = existingTransactionCount;
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public UUID getExistingStatementId() {
        return existingStatementId;
    }

    public OffsetDateTime getExistingUploadDate() {
        return existingUploadDate;
    }

    public int getExistingTransactionCount() {
        return existingTransactionCount;
    }
}
