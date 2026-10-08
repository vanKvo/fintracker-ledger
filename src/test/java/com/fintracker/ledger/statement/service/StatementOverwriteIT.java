package com.fintracker.ledger.statement.service;

import com.fintracker.ledger.shared.UserContextHolder;
import com.fintracker.ledger.statement.dto.InitiateStatementUploadRequest;
import com.fintracker.ledger.statement.repository.StatementRepository;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-STMT-05 against a real database. The unit tests prove the service asks the repository to
 * delete; only this class can prove the delete actually removes the statement's transactions.
 *
 * <p>That distinction is the whole reason this file exists. V1 declared
 * transactions.statement_id ON DELETE SET NULL, so before V18 the delete ORPHANED transactions —
 * they stayed in the account, still counted, with a NULL statement_id — while every mocked test
 * passed and the service even logged "Cascaded transactions removed". An overwrite on that
 * schema silently doubles the account's transactions, which is a correctness bug in the user's
 * money, not a cosmetic one.
 */
@DisplayName("REQ-STMT-05 overwrite against a real database")
class StatementOverwriteIT extends AbstractIntegrationTest {

    @Autowired private StatementService statementService;
    @Autowired private StatementRepository statementRepository;

    private InitiateStatementUploadRequest request(UUID accountId, String contentHash,
                                                    UUID overwriteStatementId) {
        return new InitiateStatementUploadRequest(
                accountId, "August statement", "CSV", "statement.csv", "chase",
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), contentHash, overwriteStatementId);
    }

    @Test
    @DisplayName("REQ-STMT-05: overwriting removes the old statement's transactions, so the "
            + "replacement is a clean swap rather than a doubling")
    void overwriteRemovesTheOldStatementsTransactions() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        UserContextHolder.set(userId);

        var original = statementService.initiateUpload(
                request(accountId, "a".repeat(64), null), userId);
        insertTransactionAsSuperuser(accountId, userId, original.jobId(), "Rent");
        insertTransactionAsSuperuser(accountId, userId, original.jobId(), "Payroll");
        assertThat(countTransactions(accountId)).isEqualTo(2);

        // A corrected re-export of the same period: different bytes, same month, overwrite chosen.
        statementService.initiateUpload(
                request(accountId, "b".repeat(64), original.jobId()), userId);

        // If the FK were still ON DELETE SET NULL these two rows would survive with a NULL
        // statement_id and the account would carry them forever alongside whatever the new import
        // brings.
        assertThat(countTransactions(accountId))
                .as("the replaced statement's transactions must be gone, not orphaned")
                .isZero();
        assertThat(statementRepository.findByIdAndUserId(original.jobId(), userId))
                .as("the replaced statement itself must be gone")
                .isEmpty();
    }

    @Test
    @DisplayName("REQ-STMT-05: after an overwrite the account holds exactly one statement for "
            + "that month — the replacement, not both")
    void afterOverwriteExactlyOneStatementRemains() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        UserContextHolder.set(userId);

        var original = statementService.initiateUpload(
                request(accountId, "c".repeat(64), null), userId);
        var replacement = statementService.initiateUpload(
                request(accountId, "d".repeat(64), original.jobId()), userId);

        // The month is the same for both; the unique index on (account_id, statement_month) would
        // have rejected the replacement outright had the delete not really happened first.
        var statements = statementRepository.findAllByUserId(userId);
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).statementId()).isEqualTo(replacement.jobId());
    }

    // Multi-tenant: overwrite deletes data. A client-supplied statement id that the service failed
    // to bind to the caller would make this endpoint a way for any authenticated user to erase any
    // other user's statement — and, with V18's cascade, their transactions along with it.
    @Test
    @DisplayName("REQ-STMT-05 multi-tenant: overwriting another tenant's statement deletes nothing "
            + "of theirs")
    @Tag("multi-tenant")
    void overwritingAnotherTenantsStatementDeletesNothing() throws SQLException {
        var victim = UUID.randomUUID();
        var victimAccount = insertAccountAsSuperuser(victim);
        UserContextHolder.set(victim);
        var victimStatement = statementService.initiateUpload(
                request(victimAccount, "e".repeat(64), null), victim);
        insertTransactionAsSuperuser(victimAccount, victim, victimStatement.jobId(), "Rent");

        var attacker = UUID.randomUUID();
        var attackerAccount = insertAccountAsSuperuser(attacker);
        UserContextHolder.set(attacker);

        // The attacker is a legitimate tenant naming a statement id that is not theirs.
        assertThatThrownBy(() -> statementService.initiateUpload(
                request(attackerAccount, "f".repeat(64), victimStatement.jobId()), attacker))
                .isInstanceOf(com.fintracker.ledger.statement.exception.StatementNotFoundException.class);

        UserContextHolder.set(victim);
        assertThat(statementRepository.findByIdAndUserId(victimStatement.jobId(), victim))
                .as("the victim's statement must be untouched")
                .isPresent();
        assertThat(countTransactions(victimAccount))
                .as("and so must its transactions")
                .isEqualTo(1);
    }

    private int countTransactions(UUID accountId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM ledger.transactions WHERE account_id = ?")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
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

    private void insertTransactionAsSuperuser(UUID accountId, UUID userId, UUID statementId, String merchant)
            throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ledger.transactions
                         (transaction_id, account_id, user_id, statement_id, amount, merchant,
                          category, tx_date, source, type, direction, status)
                     VALUES (?, ?, ?, ?, -25.00, ?, 'Groceries', '2026-08-15',
                             'STATEMENT_UPLOAD', 'EXPENSE', 'DEBIT', 'PENDING')
                     """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, accountId);
            ps.setObject(3, userId);
            ps.setObject(4, statementId);
            ps.setString(5, merchant);
            ps.execute();
        }
    }
}
