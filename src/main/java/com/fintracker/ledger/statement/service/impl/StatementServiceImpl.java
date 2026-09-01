package com.fintracker.ledger.statement.service.impl;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.dto.InitiateStatementUploadRequest;
import com.fintracker.ledger.statement.dto.StatementUploadResponse;
import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.repository.StatementRepository;
import com.fintracker.ledger.statement.service.S3PresignService;
import com.fintracker.ledger.statement.service.StatementService;
import com.fintracker.ledger.statement.exception.StatementNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
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

        var statementId = UUID.randomUUID();
        var presigned = s3PresignService.presignStatementUpload(
                userId, statementId, request.accountId(), request.bankId(), request.fileName());

        var created = statementRepository.insert(
                statementId, request.accountId(), presigned.s3ObjectKey(), request.statementMonth(),
                request.description(), request.sourceFormat(), request.bankId());

        log.info("Initiated statement upload statementId={} userId={} sourceFormat={}",
                created.statementId(), userId, request.sourceFormat());

        return new StatementUploadResponse(created.statementId(), presigned.url(), presigned.s3ObjectKey());
    }
}
