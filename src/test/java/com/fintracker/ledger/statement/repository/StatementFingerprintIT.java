package com.fintracker.ledger.statement.repository;

import com.fintracker.ledger.shared.UserContextHolder;
import com.fintracker.ledger.statement.exception.StatementNotFoundException;
import com.fintracker.ledger.statement.service.StatementService;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-STMT-04 against a real database: the fingerprint written by one call is the fingerprint the
 * next call matches on.
 *
 * <p>Every mocked test in this suite would pass against an implementation that queries
 * content_fingerprint but never stores it — leaving REQ-STMT-04 permanently unable to match
 * anything in production. This class is the one that would not, so it is deliberately a
 * write-then-read round trip rather than two separately-stubbed halves.
 */
@DisplayName("REQ-STMT-04 content fingerprint against a real database")
class StatementFingerprintIT extends AbstractIntegrationTest {

    @Autowired private StatementService statementService;
    @Autowired private StatementRepository statementRepository;

    @Test
    @DisplayName("REQ-STMT-04: a fingerprint recorded on one statement is found by a later lookup "
            + "for the same account")
    void aRecordedFingerprintIsFoundByALaterLookup() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var statementId = insertStatementAsSuperuser(accountId, userId, "a".repeat(64));
        var fingerprint = "b".repeat(64);
        UserContextHolder.set(userId);

        statementService.recordContentFingerprint(statementId, userId, fingerprint);

        var match = statementService.checkForDuplicateByContentFingerprint(accountId, fingerprint);
        assertThat(match).isPresent();
        assertThat(match.get().existingStatementId()).isEqualTo(statementId);
    }

    @Test
    @DisplayName("REQ-STMT-04: the stored fingerprint reads back on the statement itself, so the "
            + "value that was written is the value that is held")
    void theStoredFingerprintReadsBackOnTheStatement() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var statementId = insertStatementAsSuperuser(accountId, userId, "9".repeat(64));
        var fingerprint = "8".repeat(64);
        UserContextHolder.set(userId);

        statementService.recordContentFingerprint(statementId, userId, fingerprint);

        // Reading it back through the domain model, not just matching on it, is what catches a
        // write that landed in the wrong column or was silently truncated by the CHAR(64) type.
        assertThat(statementRepository.findByAccountIdAndContentFingerprint(accountId, fingerprint))
                .get()
                .extracting(com.fintracker.ledger.statement.model.Statement::contentFingerprint)
                .isEqualTo(fingerprint);
    }

    @Test
    @DisplayName("REQ-STMT-04: a statement with no fingerprint yet matches nothing — a NULL column "
            + "must never collide with another NULL")
    void aStatementWithoutAFingerprintMatchesNothing() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        insertStatementAsSuperuser(accountId, userId, "c".repeat(64));
        UserContextHolder.set(userId);

        // Two statements mid-processing both have NULL fingerprints; treating that as a match
        // would flag every in-flight upload as a duplicate of every other one.
        assertThat(statementRepository.findByAccountIdAndContentFingerprint(accountId, null)).isEmpty();
    }

    // Multi-tenant: REQ-STMT-04 reuses REQ-STMT-03's account-scoping rule. A global fingerprint
    // lookup would both block a legitimate upload and disclose that some other account holds a
    // statement with the same contents.
    @Test
    @DisplayName("REQ-STMT-04 multi-tenant: the same fingerprint in another tenant's account is "
            + "not reported as a duplicate")
    @Tag("multi-tenant")
    void theSameFingerprintInAnotherAccountIsNotADuplicate() throws SQLException {
        var userA = UUID.randomUUID();
        var accountA = insertAccountAsSuperuser(userA);
        var statementA = insertStatementAsSuperuser(accountA, userA, "d".repeat(64));
        var sharedFingerprint = "e".repeat(64);

        var userB = UUID.randomUUID();
        var accountB = insertAccountAsSuperuser(userB);

        UserContextHolder.set(userA);
        statementService.recordContentFingerprint(statementA, userA, sharedFingerprint);

        UserContextHolder.set(userB);
        assertThat(statementService.checkForDuplicateByContentFingerprint(accountB, sharedFingerprint))
                .as("user B's account must not match a fingerprint stored in user A's")
                .isEmpty();
    }

    // Multi-tenant, write side: proving the caller is the pipeline does not authorize it to write
    // into an arbitrary tenant's statement. The UPDATE is scoped by user_id, so a foreign
    // statement is a no-op the service reports as not found.
    @Test
    @DisplayName("REQ-STMT-04 multi-tenant: recording a fingerprint onto another tenant's "
            + "statement neither succeeds nor modifies their row")
    @Tag("multi-tenant")
    void recordingAFingerprintOntoAnotherTenantsStatementIsRejected() throws SQLException {
        var victim = UUID.randomUUID();
        var victimAccount = insertAccountAsSuperuser(victim);
        var victimStatement = insertStatementAsSuperuser(victimAccount, victim, "f".repeat(64));

        var attacker = UUID.randomUUID();
        insertAccountAsSuperuser(attacker);
        UserContextHolder.set(attacker);

        assertThatThrownBy(() -> statementService.recordContentFingerprint(
                victimStatement, attacker, "1".repeat(64)))
                .isInstanceOf(StatementNotFoundException.class);

        // The victim's row must still carry no fingerprint — a rejected write that had already
        // landed would be worse than one that errored.
        UserContextHolder.set(victim);
        assertThat(statementService.checkForDuplicateByContentFingerprint(victimAccount, "1".repeat(64)))
                .isEmpty();
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

    private UUID insertStatementAsSuperuser(UUID accountId, UUID userId, String contentHash)
            throws SQLException {
        var statementId = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ledger.statements
                         (statement_id, account_id, user_id, s3_object_key, status,
                          source_format, content_hash, opening_date, closing_date)
                     VALUES (?, ?, ?, 'statements/x/y/z.csv', 'COMPLETED', 'CSV', ?, ?, ?)
                     """)) {
            ps.setObject(1, statementId);
            ps.setObject(2, accountId);
            ps.setObject(3, userId);
            ps.setString(4, contentHash);
            ps.setObject(5, LocalDate.of(2026, 8, 3));
            ps.setObject(6, LocalDate.of(2026, 9, 2));
            ps.execute();
        }
        return statementId;
    }
}
