package com.fintracker.ledger.statement.service;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.dto.InitiateStatementUploadRequest;
import com.fintracker.ledger.statement.exception.DuplicateStatementException;
import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.repository.StatementRepository;
import com.fintracker.ledger.statement.service.S3PresignService;
import com.fintracker.ledger.statement.service.impl.StatementServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage map to prompt.md:
 *
 *   - REQ-STMT-03 "Recognizing the Exact Same File Uploaded Again" — CheckForDuplicateByContentHash,
 *     InitiateUpload and RequestValidation nested classes below.
 *   - REQ-STMT-06 "Giving a Clear Answer When a Month Is Already Taken" — SameMonthDuplicate;
 *     REQ-STMT-06 folds into checkForDuplicateByContentHash rather than having its own method.
 *   - REQ-STMT-07 "Collecting the Statement Date Range Upfront" — InitiateUpload's date-range
 *     cases and RequestValidation.
 *
 * <p>Signatures follow prompt.md exactly. Three consequences worth stating, because each one
 * removes a test a reader might expect to find here:
 *
 * <ul>
 *   <li>{@code checkForDuplicateByContentHash(accountId, contentHash, statementMonth)} takes no
 *       userId — the accountId ownership guard lives in the caller (initiateUpload, and
 *       InternalStatementController for the internal route), not inside the shared helper. The
 *       tenant-isolation counterpart is proved where it is real, in JooqStatementRepositoryIT and
 *       StatementUploadDuplicateIT.</li>
 *   <li>{@code contentHash} is REQUIRED (REQ-STMT-03 Constraints: "an upload that arrives without
 *       one is rejected outright"), so there is deliberately no "skips the check when null" case —
 *       that path no longer exists. Its absence is asserted at the DTO layer instead.</li>
 *   <li>{@code statementMonth} is no longer passed to {@code statementRepository.insert} at all —
 *       REQ-STMT-07 makes it a generated column derived from {@code closing_date}. The only
 *       service-level observation of the derivation is which month the duplicate check is run
 *       for; the database's own derivation is asserted in JooqStatementRepositoryIT.</li>
 * </ul>
 *
 * <p>{@code existingTransactionCount} is read off the matched Statement's own txCount, which the
 * record already carries — prompt.md introduces no transaction-counting repository method.
 *
 * FAIL-TO-PASS: StatementServiceImpl's duplicate-detection and date-range logic did not exist
 * before this change — initiateUpload took a statementMonth field straight off the request and had
 * no duplicate check at all.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementService Unit Tests")
class StatementServiceTest {

    @Mock private StatementRepository statementRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private S3PresignService s3PresignService;

    private StatementService statementService;
    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        statementService = new StatementServiceImpl(statementRepository, accountRepository, s3PresignService);
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
    }

    // Record shape per REQ-STMT-07: ..., bankId, contentHash, openingDate, closingDate, counts.
    // contentHash is a placeholder here — the repository is mocked and keyed on the queried hash,
    // so the value carried back on the match is never what these assertions turn on.
    private Statement existingStatement(UUID statementId, LocalDate statementMonth, int txCount) {
        return new Statement(statementId, accountId, "statements/x/y/z.csv", statementMonth,
                Statement.StatementStatus.COMPLETED, "desc", OffsetDateTime.parse("2026-08-27T10:15:00Z"),
                "CSV", "chase", "0".repeat(64),
                statementMonth.withDayOfMonth(3), statementMonth.withDayOfMonth(28),
                txCount, 0, txCount);
    }

    private InitiateStatementUploadRequest validRequest(LocalDate opening, LocalDate closing, String contentHash) {
        return new InitiateStatementUploadRequest(
                accountId, "August statement", "CSV", "statement.csv", "chase",
                opening, closing, contentHash, null);
    }

    @Nested
    @DisplayName("checkForDuplicateByContentHash()")
    class CheckForDuplicateByContentHash {

        @Test
        @DisplayName("REQ-STMT-03: should find an EXACT_FILE match by content hash")
        void shouldFindExactFileMatch() {
            var existing = existingStatement(UUID.randomUUID(), LocalDate.of(2026, 8, 1), 34);
            when(statementRepository.findByAccountIdAndContentHash(accountId, "a".repeat(64)))
                    .thenReturn(Optional.of(existing));

            // statementMonth omitted — the shape InternalStatementController uses, where the
            // internal query "is never about the month check".
            var result = statementService.checkForDuplicateByContentHash(accountId, "a".repeat(64), null);

            assertThat(result).isPresent();
            assertThat(result.get().matchType()).isEqualTo(DuplicateStatementException.MatchType.EXACT_FILE);
            assertThat(result.get().existingStatementId()).isEqualTo(existing.statementId());
            assertThat(result.get().existingUploadDate()).isEqualTo(existing.uploadDate());
            assertThat(result.get().existingTransactionCount()).isEqualTo(34);
        }

        @Test
        @DisplayName("REQ-STMT-06: should find a SAME_MONTH match when no content hash match exists")
        void shouldFindSameMonthMatch() {
            var month = LocalDate.of(2026, 8, 1);
            var existing = existingStatement(UUID.randomUUID(), month, 10);
            when(statementRepository.findByAccountIdAndContentHash(accountId, "c".repeat(64)))
                    .thenReturn(Optional.empty());
            when(statementRepository.findByAccountIdAndStatementMonth(accountId, month))
                    .thenReturn(Optional.of(existing));

            var result = statementService.checkForDuplicateByContentHash(accountId, "c".repeat(64), month);

            assertThat(result).isPresent();
            assertThat(result.get().matchType()).isEqualTo(DuplicateStatementException.MatchType.SAME_MONTH);
            assertThat(result.get().existingTransactionCount()).isEqualTo(10);
        }

        // REQ-STMT-03 Constraints, "the most specific answer wins", and REQ-STMT-06's "this is the
        // fallback explanation, not the first one". The easiest rule in the document to get
        // backwards, and the user-visible symptom of getting it backwards (a vaguer message than
        // the system actually had evidence for) is not something any other test would catch.
        @Test
        @DisplayName("REQ-STMT-03/06 specificity priority: EXACT_FILE must take priority over "
                + "SAME_MONTH when a statement matches both, and only one match is ever returned")
        void exactFileTakesPriorityOverSameMonth() {
            var month = LocalDate.of(2026, 8, 1);
            var exactFileMatch = existingStatement(UUID.randomUUID(), month, 5);
            when(statementRepository.findByAccountIdAndContentHash(accountId, "d".repeat(64)))
                    .thenReturn(Optional.of(exactFileMatch));

            var result = statementService.checkForDuplicateByContentHash(accountId, "d".repeat(64), month);

            assertThat(result).isPresent();
            assertThat(result.get().matchType()).isEqualTo(DuplicateStatementException.MatchType.EXACT_FILE);
            assertThat(result.get().existingStatementId()).isEqualTo(exactFileMatch.statementId());
            // "SAME_MONTH is only consulted when no exact-file match exists" — not merely
            // overridden afterwards.
            verify(statementRepository, never()).findByAccountIdAndStatementMonth(any(), any());
        }

        @Test
        @DisplayName("should return empty when neither contentHash nor statementMonth match anything")
        void shouldReturnEmptyWhenNoMatch() {
            var month = LocalDate.of(2026, 8, 1);
            when(statementRepository.findByAccountIdAndContentHash(accountId, "f".repeat(64)))
                    .thenReturn(Optional.empty());
            when(statementRepository.findByAccountIdAndStatementMonth(accountId, month))
                    .thenReturn(Optional.empty());

            var result = statementService.checkForDuplicateByContentHash(accountId, "f".repeat(64), month);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("initiateUpload()")
    class InitiateUpload {

        @Test
        @DisplayName("REQ-STMT-07: should reject closingDate before openingDate")
        void shouldRejectClosingDateBeforeOpeningDate() {
            when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(true);
            var request = validRequest(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 8, 3), "a".repeat(64));

            assertThatThrownBy(() -> statementService.initiateUpload(request, userId))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(statementRepository, never()).insert(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        // Boundary: a single-day statement (opening == closing) must be accepted — REQ-STMT-07
        // explicitly says only "before" is rejected, not "not after".
        @Test
        @DisplayName("REQ-STMT-07: should accept closingDate equal to openingDate "
                + "(single-day statement)")
        void shouldAcceptClosingDateEqualToOpeningDate() {
            var day = LocalDate.of(2026, 8, 15);
            when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(true);
            when(statementRepository.findByAccountIdAndStatementMonth(accountId, LocalDate.of(2026, 8, 1)))
                    .thenReturn(Optional.empty());
            when(s3PresignService.presignStatementUpload(any(), any(), any(), any(), any()))
                    .thenReturn(new S3PresignService.PresignedUpload("https://s3/upload", "key"));
            when(statementRepository.insert(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(existingStatement(UUID.randomUUID(), LocalDate.of(2026, 8, 1), 0));

            var request = validRequest(day, day, "a".repeat(64));

            assertThatCode(() -> statementService.initiateUpload(request, userId)).doesNotThrowAnyException();
        }

        // REQ-STMT-07 removes statementMonth from insert() entirely (the database generates it from
        // closing_date), so which month the service DERIVED is only observable in which month it
        // runs REQ-STMT-06's duplicate query for. Deriving it from openingDate instead would look
        // for a collision in the wrong month and let a duplicate through.
        @Test
        @DisplayName("REQ-STMT-07: the grouping month is derived from closingDate, truncated to "
                + "the first of that month, and used for the same-month duplicate check")
        void shouldDeriveStatementMonthFromClosingDate() {
            when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(true);
            when(statementRepository.findByAccountIdAndStatementMonth(accountId, LocalDate.of(2026, 9, 1)))
                    .thenReturn(Optional.empty());
            when(s3PresignService.presignStatementUpload(any(), any(), any(), any(), any()))
                    .thenReturn(new S3PresignService.PresignedUpload("https://s3/upload", "key"));
            when(statementRepository.insert(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(existingStatement(UUID.randomUUID(), LocalDate.of(2026, 9, 1), 0));

            // Statement spans Aug 3 - Sep 2 — the closing date lands in September, so it must be
            // grouped under September, not the month most of its transactions fall in.
            var request = validRequest(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), "a".repeat(64));

            statementService.initiateUpload(request, userId);

            verify(statementRepository).findByAccountIdAndStatementMonth(accountId, LocalDate.of(2026, 9, 1));
            // Signature per REQ-STMT-07: statementId, accountId, s3ObjectKey, openingDate,
            // closingDate, contentHash, description, sourceFormat, bankId — no statementMonth.
            // Asserting contentHash here is what pins REQ-STMT-03's "it is persisted on the
            // statement it belongs to, which is what makes the next upload's check possible".
            verify(statementRepository).insert(any(), eq(accountId), any(),
                    eq(LocalDate.of(2026, 8, 3)), eq(LocalDate.of(2026, 9, 2)), eq("a".repeat(64)),
                    any(), any(), any());
        }

        @Test
        @DisplayName("REQ-STMT-03: should throw DuplicateStatementException and create nothing "
                + "when contentHash matches an existing statement")
        void shouldThrowOnExactFileDuplicate() {
            var existing = existingStatement(UUID.randomUUID(), LocalDate.of(2026, 8, 1), 34);
            when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(true);
            when(statementRepository.findByAccountIdAndContentHash(accountId, "a".repeat(64)))
                    .thenReturn(Optional.of(existing));

            var request = validRequest(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), "a".repeat(64));

            assertThatThrownBy(() -> statementService.initiateUpload(request, userId))
                    .isInstanceOf(DuplicateStatementException.class)
                    .satisfies(ex -> {
                        var dup = (DuplicateStatementException) ex;
                        assertThat(dup.getMatchType()).isEqualTo(DuplicateStatementException.MatchType.EXACT_FILE);
                        assertThat(dup.getExistingStatementId()).isEqualTo(existing.statementId());
                        assertThat(dup.getExistingTransactionCount()).isEqualTo(34);
                    });

            // "it stops before wasting any time uploading or processing the file" — no S3 URL and
            // no statement row once a duplicate is recognized.
            verify(s3PresignService, never()).presignStatementUpload(any(), any(), any(), any(), any());
            verify(statementRepository, never()).insert(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        // Multi-tenant: prompt.md places the accountId ownership guard at step 1 of initiateUpload's
        // documented order of checks, so it is asserted here rather than inside the duplicate
        // helper, which takes no userId.
        @Test
        @DisplayName("multi-tenant: should throw IllegalArgumentException when accountId doesn't "
                + "belong to the user, before any duplicate query or S3 URL")
        void shouldRejectAccountNotOwnedByUser() {
            when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(false);
            var request = validRequest(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), "a".repeat(64));

            assertThatThrownBy(() -> statementService.initiateUpload(request, userId))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(statementRepository, never()).findByAccountIdAndContentHash(any(), any());
            verify(statementRepository, never()).findByAccountIdAndStatementMonth(any(), any());
            verify(s3PresignService, never()).presignStatementUpload(any(), any(), any(), any(), any());
        }
    }

    // ADDED. REQ-STMT-06's own requirement — "Recognize this specific situation ... and show the
    // user a clear, specific message" — had no test at the initiateUpload level: the InitiateUpload
    // class above only proves the EXACT_FILE branch throws. Without this, an implementation that
    // wires the duplicate check in but only surfaces EXACT_FILE (leaving the month collision to
    // fall through to the DB unique index and the generic 500 REQ-STMT-06 exists to remove) passes
    // the whole suite.
    @Nested
    @DisplayName("initiateUpload() — REQ-STMT-06 same-month duplicate")
    class SameMonthDuplicate {

        @Test
        @DisplayName("REQ-STMT-06: a genuinely different file in an already-taken month throws "
                + "DuplicateStatementException(SAME_MONTH) carrying the existing statement's "
                + "id/date/count, and creates nothing")
        void shouldThrowOnSameMonthDuplicate() {
            var month = LocalDate.of(2026, 9, 1);
            var existing = existingStatement(UUID.randomUUID(), month, 7);
            when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(true);
            // A different file: the hash is well-formed and required, it simply matches nothing.
            when(statementRepository.findByAccountIdAndContentHash(accountId, "b".repeat(64)))
                    .thenReturn(Optional.empty());
            when(statementRepository.findByAccountIdAndStatementMonth(accountId, month))
                    .thenReturn(Optional.of(existing));

            var request = validRequest(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), "b".repeat(64));

            assertThatThrownBy(() -> statementService.initiateUpload(request, userId))
                    .isInstanceOf(DuplicateStatementException.class)
                    .satisfies(ex -> {
                        var dup = (DuplicateStatementException) ex;
                        assertThat(dup.getMatchType())
                                .isEqualTo(DuplicateStatementException.MatchType.SAME_MONTH);
                        assertThat(dup.getExistingStatementId()).isEqualTo(existing.statementId());
                        assertThat(dup.getExistingUploadDate()).isEqualTo(existing.uploadDate());
                        assertThat(dup.getExistingTransactionCount()).isEqualTo(7);
                    });

            verify(s3PresignService, never()).presignStatementUpload(any(), any(), any(), any(), any());
            verify(statementRepository, never()).insert(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ADDED. Two of this change's hardest requirements are enforced by DTO annotations, not by
    // service code, so no service-level test can observe either one:
    //   - REQ-STMT-07 makes openingDate/closingDate required for EVERY sourceFormat, CSV included,
    //     "replacing today's conditional requirement in StatementServiceImpl that only enforces
    //     them for PDF/IMAGE". An implementation that keeps the old conditional and forgets @NotNull
    //     passes every other test in this file.
    //   - REQ-STMT-03 makes contentHash REQUIRED, explicitly so that no client can "opt itself out
    //     of duplicate detection simply by omitting a field". @NotNull is what makes that true.
    @Nested
    @DisplayName("InitiateStatementUploadRequest bean validation")
    class RequestValidation {

        private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        private Set<String> violatedProperties(InitiateStatementUploadRequest request) {
            return validator.validate(request).stream()
                    .map(v -> v.getPropertyPath().toString())
                    .collect(Collectors.toSet());
        }

        private InitiateStatementUploadRequest csvRequest(LocalDate opening, LocalDate closing, String hash) {
            return new InitiateStatementUploadRequest(
                    accountId, "August statement", "CSV", "statement.csv", "chase",
                    opening, closing, hash, null);
        }

        @Test
        @DisplayName("REQ-STMT-07: a CSV upload with no openingDate is rejected")
        void csvUploadRequiresOpeningDate() {
            assertThat(violatedProperties(
                    csvRequest(null, LocalDate.of(2026, 9, 2), "a".repeat(64))))
                    .contains("openingDate");
        }

        @Test
        @DisplayName("REQ-STMT-07: a CSV upload with no closingDate is rejected")
        void csvUploadRequiresClosingDate() {
            assertThat(violatedProperties(
                    csvRequest(LocalDate.of(2026, 8, 3), null, "a".repeat(64))))
                    .contains("closingDate");
        }

        // REQ-STMT-03 Constraints: "The fingerprint is required, not optional. An upload that
        // arrives without one is rejected outright rather than accepted and processed without the
        // check." A @Pattern without a @NotNull would let null through silently, which is exactly
        // the opt-out the constraint forbids — so this asserts the omission case specifically.
        @Test
        @DisplayName("REQ-STMT-03: an upload with no contentHash at all is rejected")
        void contentHashIsRequired() {
            assertThat(violatedProperties(
                    csvRequest(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), null)))
                    .contains("contentHash");
        }

        @Test
        @DisplayName("REQ-STMT-03: contentHash must be exactly 64 lowercase hex characters")
        void contentHashMustBe64LowercaseHexCharacters() {
            assertThat(violatedProperties(
                    csvRequest(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), "A".repeat(64))))
                    .as("uppercase hex is outside the [a-f0-9]{64} pattern REQ-STMT-03 specifies")
                    .contains("contentHash");
            assertThat(violatedProperties(
                    csvRequest(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), "a".repeat(63))))
                    .contains("contentHash");
            assertThat(violatedProperties(
                    csvRequest(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), "a1b2c3d4".repeat(8))))
                    .as("a well-formed request must produce no violations at all")
                    .isEmpty();
        }
    }
}
