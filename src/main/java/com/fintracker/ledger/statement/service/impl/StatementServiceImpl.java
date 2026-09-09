package com.fintracker.ledger.statement.service.impl;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.dto.InitiateStatementUploadRequest;
import com.fintracker.ledger.statement.dto.StatementUploadResponse;
import com.fintracker.ledger.statement.exception.DuplicateStatementException;
import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.repository.StatementRepository;
import com.fintracker.ledger.statement.service.S3PresignService;
import com.fintracker.ledger.statement.service.StatementService;
import com.fintracker.ledger.statement.exception.StatementNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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

    @Override
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

        // REQ-STMT-03/06: synchronous duplicate check before any S3 URL or statement row
        // is created — a pre-check (not a caught constraint violation) because the 409
        // response needs the existing statement's id/date/count. The request's
        // overwriteStatementId is REQ-STMT-05's overwrite flow; until that lands, a
        // duplicate remains a 409.
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
}
