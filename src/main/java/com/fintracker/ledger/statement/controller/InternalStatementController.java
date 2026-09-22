package com.fintracker.ledger.statement.controller;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.dto.DuplicateCheckResponse;
import com.fintracker.ledger.statement.dto.RecordContentFingerprintRequest;
import com.fintracker.ledger.statement.dto.StatementOwnerResponse;
import com.fintracker.ledger.statement.exception.StatementNotFoundException;
import com.fintracker.ledger.statement.service.StatementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REQ-STMT-03: internal-only duplicate check for the data-pipeline's Gatekeeper, called
 * for the server-side content-hash recompute right after upload — the Ledger does not
 * rely on the browser-sent hash alone. Protected by the same SigV4 +
 * {@code InternalCallerFilter} + {@code X-Internal-User-Id} pattern as REQ-STMT-02's
 * bulk-create endpoint.
 *
 * <p>Kept separate from the user-facing {@link StatementController} so the two
 * authentication models are never mixed on the same route.
 */
@RestController
@RequestMapping("/api/v1/ledger/statements/internal")
public class InternalStatementController {

    // The same two beans StatementServiceImpl itself uses for these two jobs, so the
    // ownership guard here is literally the same call, not a reimplementation of it.
    private final StatementService statementService;
    private final AccountRepository accountRepository;

    public InternalStatementController(StatementService statementService,
                                       AccountRepository accountRepository) {
        this.statementService = statementService;
        this.accountRepository = accountRepository;
    }

    @GetMapping("/duplicate-check")
    public ResponseEntity<DuplicateCheckResponse> checkDuplicate(
            @RequestParam UUID accountId,
            @RequestParam(required = false) String contentHash,
            @RequestParam(required = false) String contentFingerprint,
            @RequestAttribute("userId") UUID userId) {
        // Ownership guard first — the same one StatementServiceImpl.initiateUpload runs —
        // before either duplicate query, so an internal caller cannot probe other users'
        // accounts for statement existence.
        if (!accountRepository.existsByIdAndUserId(accountId, userId)) {
            throw new IllegalArgumentException(
                    "Account %s does not belong to the requesting user.".formatted(accountId));
        }

        // contentHash and contentFingerprint are mutually exclusive on a single call —
        // the Gatekeeper asks one question per pipeline stage, never both at once.
        // Both present, or neither, is a client error rather than silently picking one.
        boolean hasContentHash = contentHash != null && !contentHash.isBlank();
        boolean hasContentFingerprint = contentFingerprint != null && !contentFingerprint.isBlank();
        if (hasContentHash == hasContentFingerprint) {
            throw new IllegalArgumentException(
                    "Exactly one of contentHash or contentFingerprint must be provided.");
        }
        // REQ-STMT-04 / REQ-STMT-08: the Gatekeeper calls this endpoint at two different pipeline
        // stages — right after upload with its recomputed contentHash, and again after parsing
        // with the aggregate contentFingerprint. Each call answers exactly the question it was
        // asked. statementMonth is omitted from the hash branch: this internal query is only ever
        // about content, never about the month rule.
        var result = hasContentFingerprint
                ? statementService.checkForDuplicateByContentFingerprint(accountId, contentFingerprint)
                : statementService.checkForDuplicateByContentHash(accountId, contentHash, null);
        return ResponseEntity.ok(result
                .map(match -> new DuplicateCheckResponse(true, match.matchType().name(),
                        match.existingStatementId(), match.existingUploadDate(),
                        match.existingTransactionCount()))
                .orElseGet(() -> new DuplicateCheckResponse(false, null, null, null, null)));
    }

    /**
     * REQ-DP-05: the Data Pipeline's S3 trigger calls this immediately after an upload lands,
     * BEFORE it knows who the statement belongs to — that is the whole reason this endpoint
     * exists, replacing the old approach of trusting user-id/account-id tags set on the S3
     * object at upload time (those tags are attacker- or bug-controlled the same way a
     * request-body id is; this is not).
     *
     * <p>Deliberately does NOT use {@code @RequestAttribute("userId")} to scope the lookup,
     * unlike every other method on this controller. {@code UserContextFilter} still runs on
     * this route (it is not scoped per-endpoint) and still requires a syntactically valid
     * {@code X-Internal-User-Id} header, but this endpoint's whole job is to answer "who owns
     * this statement" — the caller asserting an identity would be backwards. The real access
     * control here is {@code InternalCallerFilter}'s caller-ARN allow-list, exactly like every
     * other {@code /internal/*} route; a caller that passes that gate is trusted to ask this
     * question about any statement_id, since the answer is what establishes identity for the
     * rest of that pipeline run, not something the caller already had.
     */
    @GetMapping("/{id}/owner")
    public ResponseEntity<StatementOwnerResponse> getOwner(@PathVariable UUID id) {
        var owner = statementService.findStatementOwner(id)
                .orElseThrow(() -> new StatementNotFoundException(id));
        return ResponseEntity.ok(new StatementOwnerResponse(id, owner.accountId(), owner.userId()));
    }

    /**
     * REQ-STMT-04: stores the aggregate fingerprint once the pipeline has read the file. There is
     * no other write path for this value — it cannot be known at upload time, which is the whole
     * reason this check happens mid-processing rather than up front.
     */
    @PatchMapping("/{id}/content-fingerprint")
    public ResponseEntity<Void> recordContentFingerprint(
            @PathVariable UUID id,
            @Valid @RequestBody RecordContentFingerprintRequest request,
            @RequestAttribute("userId") UUID userId) {
        // Ownership is enforced inside the service, scoped into the UPDATE itself rather than
        // checked separately first, so there is no window between the check and the write.
        statementService.recordContentFingerprint(id, userId, request.contentFingerprint());
        return ResponseEntity.noContent().build();
    }
}
