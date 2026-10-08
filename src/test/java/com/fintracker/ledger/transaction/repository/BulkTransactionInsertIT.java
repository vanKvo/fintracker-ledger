package com.fintracker.ledger.transaction.repository;

import com.fintracker.ledger.shared.UserContextHolder;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import com.fintracker.ledger.transaction.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-STMT-02: proves against real Postgres what a Mockito-mocked repository structurally
 * cannot — the single multi-row {@code INSERT ... ON CONFLICT (statement_id, row_fingerprint)
 * DO NOTHING} is natively partial-tolerant:
 *
 *   1. A retried batch is a safe no-op for every row already recorded, not an error
 *      (network-hiccup retries never double-record a statement's transactions).
 *   2. A partially-new batch inserts only the rows not already recorded, in one statement.
 *   3. row_fingerprint round-trips (it is the idempotency key the ON CONFLICT targets).
 *   4. Oversized batches are chunked (1,000 rows per INSERT, so each statement stays far
 *      under Postgres's 65,535-bind-parameter ceiling) and wrapped in one transaction —
 *      a failure in a later chunk rolls the earlier ones back.
 *
 * Fixture rows are inserted via JDBC as the superuser; the calls under test run through the
 * real repository on the restricted app_user connection (RLS enforced — see
 * {@link AbstractIntegrationTest}).
 */
class BulkTransactionInsertIT extends AbstractIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    private UUID userId;
    private UUID accountId;
    private UUID statementId;

    @BeforeEach
    void fixtures() throws SQLException {
        userId = UUID.randomUUID();
        accountId = insertAccountAsSuperuser(userId);
        statementId = insertStatementAsSuperuser(accountId);
        UserContextHolder.set(userId);
    }

    @Test
    @DisplayName("a fresh batch inserts every row and persists each row's fingerprint")
    void freshBatchInsertsEveryRow() throws SQLException {
        int inserted = transactionRepository.bulkInsertIgnoringDuplicates(statementId, List.of(
                row("a".repeat(64), new BigDecimal("-25.00")),
                row("b".repeat(64), new BigDecimal("-40.10")),
                row("c".repeat(64), new BigDecimal("1500.00"))));

        assertThat(inserted).isEqualTo(3);
        assertThat(countTransactionsForStatement()).isEqualTo(3);
        assertThat(fingerprintsForStatement())
                .containsExactlyInAnyOrder("a".repeat(64), "b".repeat(64), "c".repeat(64));
    }

    @Test
    @DisplayName("a retried batch (same fingerprints) inserts nothing — the idempotency key "
            + "makes the retry a safe no-op, not an error")
    void retriedBatchIsASafeNoOp() throws SQLException {
        var rows = List.of(
                row("a".repeat(64), new BigDecimal("-25.00")),
                row("b".repeat(64), new BigDecimal("-40.10")));

        assertThat(transactionRepository.bulkInsertIgnoringDuplicates(statementId, rows)).isEqualTo(2);
        assertThat(transactionRepository.bulkInsertIgnoringDuplicates(statementId, rows))
                .as("full retry of the same batch")
                .isZero();
        assertThat(countTransactionsForStatement()).isEqualTo(2);
    }

    @Test
    @DisplayName("a partially-recorded batch inserts only the missing rows, atomically")
    void partialOverlapInsertsOnlyTheRest() throws SQLException {
        transactionRepository.bulkInsertIgnoringDuplicates(statementId, List.of(
                row("a".repeat(64), new BigDecimal("-25.00")),
                row("b".repeat(64), new BigDecimal("-40.10"))));

        int inserted = transactionRepository.bulkInsertIgnoringDuplicates(statementId, List.of(
                row("b".repeat(64), new BigDecimal("-40.10")),  // already recorded
                row("c".repeat(64), new BigDecimal("-9.99")),
                row("d".repeat(64), new BigDecimal("-12.00"))));

        assertThat(inserted).isEqualTo(2);
        assertThat(countTransactionsForStatement()).isEqualTo(4);
    }

    @Test
    @DisplayName("an empty batch is a no-op that never touches the database")
    void emptyBatchIsANoOp() throws SQLException {
        assertThat(transactionRepository.bulkInsertIgnoringDuplicates(statementId, List.of())).isZero();
        assertThat(countTransactionsForStatement()).isZero();
    }

    @Test
    @DisplayName("a batch larger than one chunk is inserted across multiple statements, "
            + "with the counts summed — 2,500 rows in, 2,500 rows recorded")
    void batchLargerThanOneChunkInsertsAcrossChunks() throws SQLException {
        int inserted = transactionRepository.bulkInsertIgnoringDuplicates(statementId, rows(2_500, 0));

        assertThat(inserted).isEqualTo(2_500);
        assertThat(countTransactionsForStatement()).isEqualTo(2_500);
    }

    @Test
    @DisplayName("a failure in a later chunk rolls the earlier chunks back — one statement "
            + "import is all-or-nothing, never a half-recorded statement")
    void failureInALaterChunkRollsBackTheWholeBatch() throws SQLException {
        var batch = new ArrayList<>(rows(1_001, 0)); // fills chunk 1, plus one row of chunk 2
        // A row the DB itself must reject: amount overflows DECIMAL(15,2) (numeric field
        // overflow). Sitting in chunk 2, it fails only AFTER chunk 1's 1,000 rows were
        // already inserted inside the same transaction — so a zero final count proves the
        // rollback, not just the rejection.
        batch.add(row("f".repeat(64), new BigDecimal("99999999999999.99")));
        batch.addAll(rows(500, 2_000));

        assertThatThrownBy(() -> transactionRepository.bulkInsertIgnoringDuplicates(statementId, batch))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countTransactionsForStatement()).isZero();
    }

    /** {@code count} well-formed rows with distinct 64-char hex fingerprints starting at {@code seed}. */
    private List<Transaction> rows(int count, int seed) {
        var list = new ArrayList<Transaction>(count);
        for (int i = 0; i < count; i++) {
            list.add(row("%064x".formatted(seed + i + 1), new BigDecimal("-1.00")));
        }
        return list;
    }

    private Transaction row(String fingerprint, BigDecimal amount) {
        return new Transaction(null, accountId, statementId, null, null,
                amount, "Merchant", "Groceries", null, List.of(),
                LocalDate.of(2026, 8, 15), Transaction.TransactionSource.STATEMENT_UPLOAD,
                Transaction.TransactionType.EXPENSE, Transaction.TransactionStatus.PENDING,
                false, false, null, fingerprint,
                Transaction.TransactionDirection.DEBIT, "USD", null, null);
    }

    private int countTransactionsForStatement() throws SQLException {
        try (Connection conn = superuserConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM ledger.transactions WHERE statement_id = ?")) {
            ps.setObject(1, statementId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private List<String> fingerprintsForStatement() throws SQLException {
        try (Connection conn = superuserConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT row_fingerprint FROM ledger.transactions WHERE statement_id = ?")) {
            ps.setObject(1, statementId);
            try (ResultSet rs = ps.executeQuery()) {
                var fingerprints = new java.util.ArrayList<String>();
                while (rs.next()) {
                    fingerprints.add(rs.getString(1));
                }
                return fingerprints;
            }
        }
    }

    private Connection superuserConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private UUID insertAccountAsSuperuser(UUID owner) throws SQLException {
        var accountId = UUID.randomUUID();
        try (Connection conn = superuserConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ledger.accounts (account_id, user_id, account_name, account_type, sync_mode)
                     VALUES (?, ?, 'Test Account', 'CHECKING', 'MANUAL')
                     """)) {
            ps.setObject(1, accountId);
            ps.setObject(2, owner);
            ps.execute();
        }
        return accountId;
    }

    private UUID insertStatementAsSuperuser(UUID accountId) throws SQLException {
        var statementId = UUID.randomUUID();
        try (Connection conn = superuserConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ledger.statements
                         (statement_id, account_id, s3_object_key, status, opening_date, closing_date, content_hash)
                     VALUES (?, ?, 'statements/test/stmt.csv', 'PROCESSING', '2026-08-03', '2026-09-02', ?)
                     """)) {
            ps.setObject(1, statementId);
            ps.setObject(2, accountId);
            ps.setObject(3, "e".repeat(64));
            ps.execute();
        }
        return statementId;
    }
}
