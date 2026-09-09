package com.fintracker.ledger.statement.service;

import com.fintracker.ledger.shared.UserContextHolder;
import com.fintracker.ledger.statement.dto.InitiateStatementUploadRequest;
import com.fintracker.ledger.statement.exception.DuplicateStatementException;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-STMT-03 / REQ-STMT-06 / REQ-STMT-07 end to end, through the real StatementServiceImpl and a
 * real Postgres: upload a statement, then upload it again and be told it already exists.
 *
 * <p>Every other duplicate-detection test in this suite either mocks StatementRepository or seeds
 * ledger.statements over raw JDBC, so all of them would still pass against an implementation that
 * queries content_hash but never stores it — leaving REQ-STMT-03 permanently unable to match
 * anything in production. This class is the one that would not.
 *
 * FAIL-TO-PASS: initiateUpload has no duplicate check, no contentHash, and no openingDate/
 * closingDate before this change.
 */
class StatementUploadDuplicateIT extends AbstractIntegrationTest {

    @Autowired
    private StatementService statementService;

    private InitiateStatementUploadRequest request(UUID accountId, String contentHash,
                                                    LocalDate opening, LocalDate closing) {
        return new InitiateStatementUploadRequest(
                accountId, "August statement", "CSV", "statement.csv", "chase",
                opening, closing, contentHash, null);
    }

    @Test
    @DisplayName("REQ-STMT-03: re-uploading the exact same file is recognized as an EXACT_FILE "
            + "duplicate of the first upload")
    void reUploadingTheSameFileIsRecognizedAsADuplicate() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var contentHash = "a".repeat(64);
        UserContextHolder.set(userId);

        var first = statementService.initiateUpload(
                request(accountId, contentHash, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2)),
                userId);

        // Same file, same account — the second attempt must stop before creating anything and
        // report the first upload back to the user.
        assertThatThrownBy(() -> statementService.initiateUpload(
                request(accountId, contentHash, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2)),
                userId))
                .isInstanceOf(DuplicateStatementException.class)
                .satisfies(ex -> {
                    var dup = (DuplicateStatementException) ex;
                    assertThat(dup.getMatchType())
                            .isEqualTo(DuplicateStatementException.MatchType.EXACT_FILE);
                    assertThat(dup.getExistingStatementId())
                            .as("the content hash recorded by the first upload must be what the "
                                    + "second upload matches against")
                            .isEqualTo(first.jobId());
                    assertThat(dup.getExistingUploadDate()).isNotNull();
                });
    }

    @Test
    @DisplayName("REQ-STMT-06/07: a genuinely different file whose closingDate falls in a month "
            + "already taken is rejected as SAME_MONTH")
    void differentFileInAnAlreadyTakenMonthIsRejectedAsSameMonth() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        UserContextHolder.set(userId);

        statementService.initiateUpload(
                request(accountId, "b".repeat(64), LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2)),
                userId);

        // A different file (different hash) and a different declared range — but its closingDate
        // still lands in September, the month the first upload was filed under. REQ-STMT-06's
        // Constraints call this out explicitly as a case that must still be reported.
        assertThatThrownBy(() -> statementService.initiateUpload(
                request(accountId, "c".repeat(64), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 9, 9)),
                userId))
                .isInstanceOf(DuplicateStatementException.class)
                .satisfies(ex -> assertThat(((DuplicateStatementException) ex).getMatchType())
                        .isEqualTo(DuplicateStatementException.MatchType.SAME_MONTH));
    }

    // Multi-tenant: REQ-STMT-03's Constraints are explicit that "this check only compares a file
    // against that same account's own previous uploads". A global content_hash lookup would both
    // block a legitimate upload and disclose that some other account already holds that exact file.
    @Test
    @DisplayName("REQ-STMT-03 multi-tenant: the identical file uploaded by a different user to "
            + "their own account is not a duplicate")
    void theSameFileUploadedByAnotherTenantIsNotADuplicate() throws SQLException {
        var userA = UUID.randomUUID();
        var userB = UUID.randomUUID();
        var accountA = insertAccountAsSuperuser(userA);
        var accountB = insertAccountAsSuperuser(userB);
        var sharedHash = "d".repeat(64);

        UserContextHolder.set(userA);
        statementService.initiateUpload(
                request(accountA, sharedHash, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2)),
                userA);

        UserContextHolder.set(userB);
        assertThatCode(() -> statementService.initiateUpload(
                request(accountB, sharedHash, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2)),
                userB))
                .doesNotThrowAnyException();
    }

    private UUID insertAccountAsSuperuser(UUID userId) throws SQLException {
        var accountId = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ledger.accounts (account_id, user_id, account_name, account_type, sync_mode)
                     VALUES (?, ?, 'Test Account', 'CHECKING', 'MANUAL')
                     """)) {
            ps.setObject(1, accountId);
            ps.setObject(2, userId);
            ps.execute();
        }
        return accountId;
    }
}
