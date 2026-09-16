package com.fintracker.ledger.statement.service;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.exception.DuplicateStatementException;
import com.fintracker.ledger.statement.exception.StatementNotFoundException;
import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.repository.StatementRepository;
import com.fintracker.ledger.statement.service.impl.StatementServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-STMT-04 — recognizing the same statement uploaded as a different file, Ledger half.
 *
 * <p>The spec scopes the Ledger narrowly here: computing the fingerprint belongs to the
 * data-pipeline, and the Ledger only (a) stores the value and (b) answers whether it matches.
 * These tests cover exactly those two jobs plus the tenancy rule around them. What the fingerprint
 * is made of, and whether it is a good discriminator, is not testable — or decidable — on this side.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("REQ-STMT-04 content fingerprint")
class StatementFingerprintTest {

    @Mock private StatementRepository statementRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private S3PresignService s3PresignService;

    private StatementServiceImpl statementService;
    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        statementService = new StatementServiceImpl(statementRepository, accountRepository, s3PresignService);
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
    }

    private Statement existingStatement(UUID statementId, int txCount) {
        return new Statement(statementId, accountId, "statements/u/s/stmt.csv",
                LocalDate.of(2026, 9, 1), Statement.StatementStatus.COMPLETED, null,
                OffsetDateTime.parse("2026-08-27T10:15:00Z"), "CSV", "chase", "a".repeat(64), null,
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), txCount, 0, txCount);
    }

    @Nested
    @DisplayName("recordContentFingerprint()")
    class RecordContentFingerprint {

        @Test
        @DisplayName("stores the fingerprint against the statement, scoped to the calling identity")
        void storesTheFingerprint() {
            var statementId = UUID.randomUUID();
            var fingerprint = "b".repeat(64);
            when(statementRepository.updateContentFingerprint(statementId, userId, fingerprint))
                    .thenReturn(true);

            statementService.recordContentFingerprint(statementId, userId, fingerprint);

            // The userId must reach the repository, not be dropped on the way: the write itself is
            // what carries the tenancy scope, so a call that forgot it would still "work" here.
            verify(statementRepository).updateContentFingerprint(statementId, userId, fingerprint);
        }

        // Multi-tenant: the pipeline is trusted to write fingerprints, not to choose whose
        // statement receives one. A statement belonging to someone else must be indistinguishable
        // from one that does not exist, or the response becomes an existence oracle for other
        // tenants' statement ids.
        @Test
        @DisplayName("multi-tenant: a statement that does not belong to the caller is reported as "
                + "not found, exactly like a nonexistent one")
        @Tag("multi-tenant")
        void aStatementBelongingToAnotherTenantIsNotFound() {
            var foreignStatementId = UUID.randomUUID();
            when(statementRepository.updateContentFingerprint(any(), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> statementService.recordContentFingerprint(
                    foreignStatementId, userId, "c".repeat(64)))
                    .isInstanceOf(StatementNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("checkForDuplicateByContentFingerprint()")
    class CheckForDuplicateByContentFingerprint {

        @Test
        @DisplayName("a match is always reported as CONTENT_FINGERPRINT, carrying the existing "
                + "statement's identity, upload date and transaction count")
        void aMatchIsReportedAsContentFingerprint() {
            var existingId = UUID.randomUUID();
            var fingerprint = "d".repeat(64);
            when(statementRepository.findByAccountIdAndContentFingerprint(accountId, fingerprint))
                    .thenReturn(Optional.of(existingStatement(existingId, 34)));

            var result = statementService.checkForDuplicateByContentFingerprint(accountId, fingerprint);

            assertThat(result).isPresent();
            // These three fields are what the user is shown to decide overwrite-or-cancel
            // (REQ-STMT-05); a match that cannot name the statement it matched is not actionable.
            assertThat(result.get().matchType())
                    .isEqualTo(DuplicateStatementException.MatchType.CONTENT_FINGERPRINT);
            assertThat(result.get().existingStatementId()).isEqualTo(existingId);
            assertThat(result.get().existingUploadDate()).isEqualTo(OffsetDateTime.parse("2026-08-27T10:15:00Z"));
            assertThat(result.get().existingTransactionCount()).isEqualTo(34);
        }

        @Test
        @DisplayName("no match returns empty — the baseline that keeps a first upload from being "
                + "flagged as a duplicate of itself")
        void noMatchReturnsEmpty() {
            when(statementRepository.findByAccountIdAndContentFingerprint(any(), any()))
                    .thenReturn(Optional.empty());

            assertThat(statementService.checkForDuplicateByContentFingerprint(accountId, "e".repeat(64)))
                    .isEmpty();
        }

        // This check runs mid-processing, long after initiateUpload settled the month question.
        // Re-applying the month rule here would resurrect a rejection the user has already passed.
        @Test
        @DisplayName("the month rule is not consulted — by this stage EXACT_FILE and SAME_MONTH "
                + "have already been decided at upload time")
        void theMonthRuleIsNotConsulted() {
            when(statementRepository.findByAccountIdAndContentFingerprint(any(), any()))
                    .thenReturn(Optional.empty());

            statementService.checkForDuplicateByContentFingerprint(accountId, "f".repeat(64));

            verify(statementRepository, never()).findByAccountIdAndStatementMonth(any(), any());
            verify(statementRepository, never()).findByAccountIdAndContentHash(any(), any());
        }
    }
}
