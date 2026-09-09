package com.fintracker.ledger.statement.controller;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.dto.DuplicateCheckResponse;
import com.fintracker.ledger.statement.service.StatementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
        if (hasContentFingerprint) {
            // REQ-STMT-04's aggregate content fingerprint has no storage on this service
            // yet; reject explicitly rather than silently answering a different question.
            throw new IllegalArgumentException(
                    "contentFingerprint duplicate checks are not supported by this service yet.");
        }

        // statementMonth is omitted — this internal query is only ever about content.
        var result = statementService.checkForDuplicateByContentHash(accountId, contentHash, null);
        return ResponseEntity.ok(result
                .map(match -> new DuplicateCheckResponse(true, match.matchType().name(),
                        match.existingStatementId(), match.existingUploadDate(),
                        match.existingTransactionCount()))
                .orElseGet(() -> new DuplicateCheckResponse(false, null, null, null, null)));
    }
}
