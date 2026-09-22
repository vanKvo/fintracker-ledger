package com.fintracker.ledger.statement.service.impl;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.dto.InitiateStatementUploadRequest;
import com.fintracker.ledger.statement.dto.StatementUploadResponse;
import com.fintracker.ledger.statement.exception.DuplicateStatementException;
import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.model.StatementOwner;
import com.fintracker.ledger.statement.repository.StatementRepository;
import com.fintracker.ledger.statement.service.S3PresignService;
import com.fintracker.ledger.statement.service.StatementService;
import com.fintracker.ledger.statement.exception.StatementNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class StatementServiceImpl implements StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementServiceImpl.class);
    private static final Set<String> VALID_SOURCE_FORMATS = Set.of("PDF", "CSV", "IMAGE");

    private final StatementRepository statementRepository;
    private final AccountRepository accountRepository;
    private final S3PresignService s3PresignService;

    public StatementServiceImpl(StatementRepository statementRepository, AccountRepository accountRepository,
                                 S3PresignService s3PresignService) {
        this.statementRepository = statementRepository;
        this.accountRepository = accountRepository;
        this.s3PresignService = s3PresignService;
    }

    @Override
    public List<Statement> getStatements(UUID userId) {
        return statementRepository.findAllByUserId(userId);
    }

    @Override
    public void updateStatus(UUID statementId, Statement.StatementStatus status) {
        statementRepository.updateStatus(statementId, status);
    }

    @Override
    public void deleteStatement(UUID statementId, UUID userId) {
        statementRepository.findByIdAndUserId(statementId, userId)
                .orElseThrow(() -> new StatementNotFoundException(statementId));

        statementRepository.deleteByIdAndUserId(statementId, userId);
        log.info("Hard-deleted statement statementId={}. Cascaded transactions removed.", statementId);
    }

    // REQ-STMT-05's delete-then-recreate (see the overwriteStatementId branch below) is two
    // separate writes with no atomicity of its own: without this, a failure anywhere between
    // the delete and the final insert (e.g. presignStatementUpload throwing) permanently loses
    // the statement being replaced, with nothing to show for it — exactly what happened when a
    // stale local S3 config made presigning fail mid-overwrite. presignStatementUpload does no
    // network I/O (SigV4 presigning is a local computation), so holding the DB transaction open
    // across it never risks holding a connection through a slow external call.
    @Override
    @Transactional
    public StatementUploadResponse initiateUpload(InitiateStatementUploadRequest request, UUID userId) {
        // Ownership check first, same guard as AccountServiceImpl.updateAccount —
        // the UI only ever offers the user's own accounts, but a direct API call
        // could submit any accountId, so the backend must not rely on that alone.
        if (!accountRepository.existsByIdAndUserId(request.accountId(), userId)) {
            throw new IllegalArgumentException(
                    "Account %s does not belong to the requesting user.".formatted(request.accountId()));
        }

        if (!VALID_SOURCE_FORMATS.contains(request.sourceFormat())) {
            throw new IllegalArgumentException("sourceFormat must be one of %s.".formatted(VALID_SOURCE_FORMATS));
        }

        // REQ-DP-01: bankId drives the data-pipeline's CSV column mapping and
        // is meaningless for any other format — required for one, forbidden
        // for the other, rather than just optional everywhere, so a client
        // mistake here surfaces immediately instead of silently producing an
        // unmapped CSV or an inexplicably-set bankId on a PDF statement.
        boolean isCsv = "CSV".equals(request.sourceFormat());
        if (isCsv && (request.bankId() == null || request.bankId().isBlank())) {
            throw new IllegalArgumentException("bankId is required when sourceFormat is CSV.");
        }
        if (!isCsv && request.bankId() != null) {
            throw new IllegalArgumentException("bankId must not be set when sourceFormat is not CSV.");
        }

        // REQ-STMT-07: the declared range is collected for every format and the grouping
        // month is derived server-side from closingDate (the month a bank statement is
        // conventionally labeled by) — never picked by the user directly. statementMonth
        // is a local value threaded into the duplicate check only; the database derives
        // its own copy from closing_date (V16 generated column), so the two can't drift.
        if (request.closingDate().isBefore(request.openingDate())) {
            throw new IllegalArgumentException("closingDate must not be before openingDate.");
        }
        LocalDate statementMonth = request.closingDate().withDayOfMonth(1);

        // REQ-STMT-05: the user answered "overwrite" on a duplicate prompt. This is the single
        // overwrite mechanism for both cases the spec describes — the up-front one (REQ-STMT-03)
        // and the mid-processing one (REQ-STMT-04), where the client simply re-submits this same
        // request with overwriteStatementId set. The Ledger does not distinguish them: a
        // mid-processing overwrite is a brand-new job, not a resumption of the paused one.
        //
        // Deleting first, then falling through to the normal create path, is what makes the
        // duplicate check below pass: the row that would have matched is gone by the time it runs.
        if (request.overwriteStatementId() != null) {
            deleteForOverwrite(request.overwriteStatementId(), request.accountId(), userId);
        }

        // REQ-STMT-03/06: synchronous duplicate check before any S3 URL or statement row
        // is created — a pre-check (not a caught constraint violation) because the 409
        // response needs the existing statement's id/date/count.
        var duplicate = checkForDuplicateByContentHash(request.accountId(), request.contentHash(), statementMonth);
        if (duplicate.isPresent()) {
            var match = duplicate.get();
            log.info("Rejected duplicate statement upload accountId={} matchType={} existingStatementId={}",
                    request.accountId(), match.matchType(), match.existingStatementId());
            throw new DuplicateStatementException(match.matchType(), match.existingStatementId(),
                    match.existingUploadDate(), match.existingTransactionCount());
        }

        var statementId = UUID.randomUUID();
        var presigned = s3PresignService.presignStatementUpload(
                userId, statementId, request.accountId(), request.bankId(), request.fileName());

        Statement created;
        try {
            created = statementRepository.insert(
                    statementId, request.accountId(), presigned.s3ObjectKey(),
                    request.openingDate(), request.closingDate(), request.contentHash(),
                    request.description(), request.sourceFormat(), request.bankId());
        } catch (DataIntegrityViolationException race) {
            // Lost a check-then-insert race: a concurrent upload of this same account
            // passed the duplicate check above while it was still uncommitted, and a
            // unique index (content-hash, V17; statement-month, V16) has now rejected
            // this row. The winner's insert is committed at this point — Postgres
            // blocks the losing insert until the winner's fate is decided — so the
            // duplicate lookup re-run below sees it, and the contract's 409 (with the
            // existing statement's details) is produced instead of a bare 500. The
            // already-generated presigned URL is simply never returned; it expires
            // harmlessly with nothing uploaded against it.
            var winner = checkForDuplicateByContentHash(
                    request.accountId(), request.contentHash(), statementMonth);
            if (winner.isEmpty()) {
                // Some other integrity violation, not the duplicate race — not ours to
                // translate into a 409.
                throw race;
            }
            var match = winner.get();
            log.info("Rejected duplicate statement upload after insert race accountId={} matchType={} existingStatementId={}",
                    request.accountId(), match.matchType(), match.existingStatementId());
            throw new DuplicateStatementException(match.matchType(), match.existingStatementId(),
                    match.existingUploadDate(), match.existingTransactionCount());
        }

        log.info("Initiated statement upload statementId={} userId={} sourceFormat={}",
                created.statementId(), userId, request.sourceFormat());

        return new StatementUploadResponse(created.statementId(),
                created.status().name(), presigned.url(), presigned.s3ObjectKey());
    }

    /**
     * REQ-STMT-05: removes the statement being replaced, after proving the caller may replace it.
     *
     * <p>Two separate checks, because they fail for different reasons and must give different
     * answers. Ownership is a 404 — a statement belonging to another tenant must be
     * indistinguishable from one that does not exist, or the response becomes an existence oracle
     * for other people's data. Account scope is a 400 — the statement is genuinely the caller's,
     * they have simply asked to replace a statement in a different account of their own, which is
     * a malformed request rather than a permission problem. Overwrite means "replace this
     * statement with this upload", never "move a statement between accounts".
     *
     * <p>The transactions that came from the replaced statement are removed with it by the
     * database (V18's ON DELETE CASCADE), which is what makes this a clean replacement rather
     * than a silent doubling of the account's transactions.
     */
    private void deleteForOverwrite(UUID overwriteStatementId, UUID accountId, UUID userId) {
        var existing = statementRepository.findByIdAndUserId(overwriteStatementId, userId)
                .orElseThrow(() -> new StatementNotFoundException(overwriteStatementId));

        if (!existing.accountId().equals(accountId)) {
            throw new IllegalArgumentException(
                    "Statement %s belongs to a different account than the upload it would replace."
                            .formatted(overwriteStatementId));
        }

        statementRepository.deleteByIdAndUserId(overwriteStatementId, userId);
        log.info("Overwrote statement statementId={} accountId={} userId={}",
                overwriteStatementId, accountId, userId);
    }

    @Override
    public void recordContentFingerprint(UUID statementId, UUID userId, String contentFingerprint) {
        // The repository scopes the UPDATE by user_id and reports whether it actually hit a row,
        // so a statement belonging to another tenant is a plain 404 here — the same answer a
        // non-existent id gets, and for the same reason.
        if (!statementRepository.updateContentFingerprint(statementId, userId, contentFingerprint)) {
            throw new StatementNotFoundException(statementId);
        }
        log.info("Recorded content fingerprint statementId={} userId={}", statementId, userId);
    }

    @Override
    public Optional<DuplicateCheckResult> checkForDuplicateByContentFingerprint(
            UUID accountId, String contentFingerprint) {
        return statementRepository.findByAccountIdAndContentFingerprint(accountId, contentFingerprint)
                .map(existing -> new DuplicateCheckResult(
                        DuplicateStatementException.MatchType.CONTENT_FINGERPRINT,
                        existing.statementId(), existing.uploadDate(), existing.txCount()));
    }

    @Override
    public Optional<DuplicateCheckResult> checkForDuplicateByContentHash(
            UUID accountId, String contentHash, LocalDate statementMonth) {
        // Specificity priority: an identical file recognized as an exact match is a more
        // precise statement of what happened than "some statement already covers this
        // month", so EXACT_FILE is evaluated first and SAME_MONTH is only the fallback.
        var exactMatch = statementRepository.findByAccountIdAndContentHash(accountId, contentHash);
        if (exactMatch.isPresent()) {
            var existing = exactMatch.get();
            return Optional.of(new DuplicateCheckResult(
                    DuplicateStatementException.MatchType.EXACT_FILE, existing.statementId(),
                    existing.uploadDate(), existing.txCount()));
        }

        if (statementMonth != null) {
            var monthMatch = statementRepository.findByAccountIdAndStatementMonth(accountId, statementMonth);
            if (monthMatch.isPresent()) {
                var existing = monthMatch.get();
                return Optional.of(new DuplicateCheckResult(
                        DuplicateStatementException.MatchType.SAME_MONTH, existing.statementId(),
                        existing.uploadDate(), existing.txCount()));
            }
        }

        return Optional.empty();
    }

    @Override
    public Optional<StatementOwner> findStatementOwner(UUID statementId) {
        return statementRepository.findOwnerByStatementId(statementId);
    }
}
