package com.fintracker.ledger.transaction.repository;

import com.fintracker.ledger.shared.UserContextHolder;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.model.TransactionFilter;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

/**
 * Proves two things a Mockito-mocked TransactionRepository structurally cannot:
 *
 *   1. Row Level Security genuinely isolates tenants at the database layer, independent of any
 *      application-level {@code WHERE user_id = ?} clause (REQ-SEC-002 / the "defense in depth"
 *      claim discussed for the ledger-spec review).
 *   2. The {@code CHECK (amount != 0)} constraint (V1__Initial_Schema.sql) actually rejects a zero
 *      amount at the database level — settling, with a real database rather than an assumption, the
 *      inferred rule behind TransactionServiceTest's UpdateAmount.shouldRejectZeroAmount F2P test.
 *
 * Fixture rows are inserted directly via JDBC as the Postgres superuser (bypassing RLS entirely for
 * setup convenience — this is fixture data, not the thing under test). Assertions run through the
 * Spring-managed DSLContext/TransactionRepository beans, which use the restricted app_user
 * connection and go through the real RlsExecuteListener — see AbstractIntegrationTest's Javadoc for
 * why that distinction matters.
 */
class JooqTransactionRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private DSLContext dsl;

    @Test
    @DisplayName("RLS: a transaction is invisible to another user even with no application-level "
            + "filter, and visible to its owner")
    void rlsIsolatesTransactionsAcrossTenantsWithNoApplicationFilter() throws SQLException {
        var userA = UUID.randomUUID();
        var userB = UUID.randomUUID();
        var accountA = insertAccountAsSuperuser(userA);
        var txA = insertTransactionAsSuperuser(accountA, new BigDecimal("-50.00"));

        UserContextHolder.set(userB);
        List<UUID> visibleToUserB = fetchAllTransactionIdsWithNoFilter();
        assertThat(visibleToUserB).doesNotContain(txA);

        UserContextHolder.set(userA);
        List<UUID> visibleToUserA = fetchAllTransactionIdsWithNoFilter();
        assertThat(visibleToUserA).contains(txA);
    }

    @Test
    @DisplayName("CHECK (amount != 0): the database rejects a transaction with a zero amount")
    void databaseRejectsZeroAmountTransaction() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        UserContextHolder.set(userId);

        var zeroAmountTransaction = new Transaction(
                null, accountId, null, null, null,
                BigDecimal.ZERO, "Test Merchant", "Groceries", "desc", List.of(),
                LocalDate.now(), Transaction.TransactionSource.MANUAL_ENTRY,
                Transaction.TransactionType.EXPENSE, Transaction.TransactionStatus.PENDING,
                false, false, null, null,
                Transaction.TransactionDirection.DEBIT, "USD", null, null);

        // The real TransactionRepository bean is wrapped by Spring's persistence exception
        // translation AOP advice, which converts jOOQ's own DataAccessException into Spring's
        // unified org.springframework.dao hierarchy — DataIntegrityViolationException here, not
        // jOOQ's native exception type.
        assertThatThrownBy(() -> transactionRepository.save(zeroAmountTransaction))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("check constraint");
    }

    // REQ-2.2 "Transaction Splitting" business constraint: "the parent transaction must be
    // dynamically hidden from active views (WHERE NOT EXISTS) the moment child rows are written."
    // FAIL-TO-PASS: JooqTransactionRepository has no such exclusion yet — findAll/sumMonthlyIncome/
    // sumMonthlyExpenses all currently still include a transaction that has split children.
    @Test
    @DisplayName("REQ-2.2 Transaction Splitting: a parent disappears from findAll once split "
            + "children exist")
    void splitParentDisappearsFromFindAllOnceItHasChildren() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var parentId = insertTransactionAsSuperuser(accountId, new BigDecimal("-100.00"));
        UserContextHolder.set(userId);

        var filter = new TransactionFilter(userId, null, null, null, null, null, null, null, null, null, 0, 50);

        assertThat(transactionRepository.findAll(filter))
                .extracting(Transaction::transactionId)
                .as("parent is visible before it has any children")
                .contains(parentId);

        transactionRepository.saveAll(List.of(
                buildChild(parentId, accountId, new BigDecimal("-60.00"), "Groceries",
                        Transaction.TransactionStatus.PENDING),
                buildChild(parentId, accountId, new BigDecimal("-40.00"), "Dining",
                        Transaction.TransactionStatus.PENDING)));

        assertThat(transactionRepository.findAll(filter))
                .extracting(Transaction::transactionId)
                .as("parent must be hidden from active views once split children exist")
                .doesNotContain(parentId);
    }

    @Test
    @DisplayName("REQ-2.2 Transaction Splitting: a parent's amount is excluded from "
            + "sumMonthlyExpenses once split children exist, even if both ended up POSTED")
    void splitParentAmountExcludedFromMonthlyExpensesOnceItHasChildren() throws SQLException {
        // Reachable today because nothing currently blocks approving an already-split parent
        // independently of its children (a separate gap, out of scope for this fix) — this test
        // proves the query-level exclusion holds regardless of how that state is reached.
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var parentId = insertPostedTransactionAsSuperuser(accountId, new BigDecimal("-100.00"));
        UserContextHolder.set(userId);

        var monthStart = LocalDate.now().withDayOfMonth(1);
        var monthEnd = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        assertThat(transactionRepository.sumMonthlyExpenses(userId, monthStart, monthEnd))
                .as("parent counts fully before it has any children")
                .isEqualByComparingTo(new BigDecimal("100.00"));

        transactionRepository.saveAll(List.of(
                buildChild(parentId, accountId, new BigDecimal("-60.00"), "Groceries",
                        Transaction.TransactionStatus.POSTED),
                buildChild(parentId, accountId, new BigDecimal("-40.00"), "Dining",
                        Transaction.TransactionStatus.POSTED)));

        assertThat(transactionRepository.sumMonthlyExpenses(userId, monthStart, monthEnd))
                .as("must be 100 (children only), not 200 (parent double-counted alongside children)")
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    // REQ-STMT-02: this is what a Mockito-mocked TransactionRepository (see
    // TransactionServiceTest.BulkCreateFromStatement) structurally cannot prove — that the unique
    // index added by V12__Add_Row_Fingerprint_To_Transactions.sql actually makes ON CONFLICT DO
    // NOTHING idempotent against a real database, not just against a stubbed return value.
    @Test
    @DisplayName("REQ-STMT-02: bulkInsertIgnoringDuplicates is idempotent — calling it twice with "
            + "identical rowFingerprints inserts the rows only once")
    void bulkInsertIsIdempotentOnRetry() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var statementId = insertStatementAsSuperuser(accountId);
        UserContextHolder.set(userId);

        var rows = List.of(
                buildStatementRow(accountId, statementId, new BigDecimal("-42.50"), "a".repeat(64)),
                buildStatementRow(accountId, statementId, new BigDecimal("-10.00"), "b".repeat(64)));

        int firstCallInserted = transactionRepository.bulkInsertIgnoringDuplicates(statementId, rows);
        assertThat(firstCallInserted).isEqualTo(2);

        // Simulates a client retry after a network hiccup — same statementId, same rowFingerprints.
        int secondCallInserted = transactionRepository.bulkInsertIgnoringDuplicates(statementId, rows);
        assertThat(secondCallInserted)
                .as("a retried batch with identical (statement_id, row_fingerprint) pairs must "
                        + "insert nothing new")
                .isZero();

        assertThat(countTransactionsInStatement(statementId))
                .as("the DB must still hold exactly 2 rows, not 4, after the retry")
                .isEqualTo(2);
    }

    // Boundary: a batch where every row is already a duplicate of a PRE-EXISTING statement (not
    // just a duplicate of the same call) must still report zero new inserts, and leave the
    // pre-existing row's data untouched (DO NOTHING, not DO UPDATE).
    @Test
    @DisplayName("REQ-STMT-02: bulkInsertIgnoringDuplicates skips a row that already exists from "
            + "an earlier, separate call, and leaves it unmodified")
    void bulkInsertSkipsRowsAlreadyPersistedFromAnEarlierCall() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var statementId = insertStatementAsSuperuser(accountId);
        UserContextHolder.set(userId);

        transactionRepository.bulkInsertIgnoringDuplicates(statementId,
                List.of(buildStatementRow(accountId, statementId, new BigDecimal("-5.00"), "c".repeat(64))));

        // A later call includes the same fingerprint (now with a different amount, simulating a
        // client bug) plus one genuinely new row.
        int inserted = transactionRepository.bulkInsertIgnoringDuplicates(statementId, List.of(
                buildStatementRow(accountId, statementId, new BigDecimal("-999.00"), "c".repeat(64)),
                buildStatementRow(accountId, statementId, new BigDecimal("-7.00"), "d".repeat(64))));

        assertThat(inserted).isEqualTo(1);
        assertThat(countTransactionsInStatement(statementId)).isEqualTo(2);
    }

    // ADDED. REQ-STMT-02 calls its multi-tenant guard "the single highest-priority item in this
    // document" and names the exact threat: "any caller who obtained the internal API key (e.g., a
    // compromised Lambda) could write transactions into any account". TransactionServiceTest proves
    // the service-layer ownership check rejects that; this proves the database refuses it too, so
    // the guard does not rest on one application-layer `if`. Note that the bulk-insert path is a
    // brand-new write path that never existed before this change — nothing else in the suite
    // establishes that it inherits the RLS protection every other write already has.
    @Test
    @DisplayName("REQ-STMT-02 multi-tenant: bulkInsertIgnoringDuplicates cannot write into another "
            + "tenant's statement — the database rejects it and nothing is persisted")
    void bulkInsertCannotWriteIntoAnotherTenantsStatement() throws SQLException {
        var victim = UUID.randomUUID();
        var attacker = UUID.randomUUID();
        var victimAccount = insertAccountAsSuperuser(victim);
        var victimStatement = insertStatementAsSuperuser(victimAccount);

        // The attacker is authenticated as themselves and simply supplies the victim's real
        // statementId/accountId in the batch — the cross-tenant data-injection vector REQ-STMT-02
        // describes.
        UserContextHolder.set(attacker);

        assertThatThrownBy(() -> transactionRepository.bulkInsertIgnoringDuplicates(
                victimStatement,
                List.of(buildStatementRow(victimAccount, victimStatement,
                        new BigDecimal("-99.00"), "1".repeat(64)))))
                .isInstanceOf(DataAccessException.class);

        UserContextHolder.set(victim);
        assertThat(countTransactionsInStatement(victimStatement))
                .as("no row from the attacker's batch may land in the victim's statement")
                .isZero();
    }

    // ADDED. The read side of the same guarantee for the new write path.
    @Test
    @DisplayName("REQ-STMT-02 multi-tenant: rows written by bulkInsertIgnoringDuplicates are "
            + "invisible to another tenant")
    void bulkInsertedRowsAreInvisibleToAnotherTenant() throws SQLException {
        var owner = UUID.randomUUID();
        var stranger = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(owner);
        var statementId = insertStatementAsSuperuser(accountId);

        UserContextHolder.set(owner);
        assertThat(transactionRepository.bulkInsertIgnoringDuplicates(statementId, List.of(
                buildStatementRow(accountId, statementId, new BigDecimal("-5.00"), "2".repeat(64)),
                buildStatementRow(accountId, statementId, new BigDecimal("-6.00"), "3".repeat(64)))))
                .isEqualTo(2);

        UserContextHolder.set(stranger);
        assertThat(fetchAllTransactionIdsWithNoFilter())
                .as("with no application-level filter at all, RLS alone must hide the rows")
                .isEmpty();
        assertThat(countTransactionsInStatement(statementId)).isZero();
    }

    // ADDED. The unique index REQ-STMT-02 specifies is on (statement_id, row_fingerprint), NOT on
    // row_fingerprint alone. The distinction is invisible in a single-tenant test and severe in a
    // multi-tenant one: row_fingerprint is a hash of date/merchant/amount only, so two different
    // users who both shopped at the same merchant for the same amount on the same day produce the
    // same fingerprint. A globally-unique index would make whichever statement was ingested second
    // silently lose that transaction — ON CONFLICT DO NOTHING reports no error, so the loss would
    // be invisible to both the user and the Data Pipeline.
    @Test
    @DisplayName("REQ-STMT-02 multi-tenant: an identical rowFingerprint under two different "
            + "tenants' statements inserts in both — the unique index is per statement, not global")
    void identicalFingerprintUnderDifferentStatementsBothInsert() throws SQLException {
        var userA = UUID.randomUUID();
        var userB = UUID.randomUUID();
        var accountA = insertAccountAsSuperuser(userA);
        var accountB = insertAccountAsSuperuser(userB);
        var statementA = insertStatementAsSuperuser(accountA);
        var statementB = insertStatementAsSuperuser(accountB);
        var sharedFingerprint = "4".repeat(64);

        UserContextHolder.set(userA);
        assertThat(transactionRepository.bulkInsertIgnoringDuplicates(statementA, List.of(
                buildStatementRow(accountA, statementA, new BigDecimal("-12.34"), sharedFingerprint))))
                .isEqualTo(1);

        UserContextHolder.set(userB);
        assertThat(transactionRepository.bulkInsertIgnoringDuplicates(statementB, List.of(
                buildStatementRow(accountB, statementB, new BigDecimal("-12.34"), sharedFingerprint))))
                .as("user B's identical-looking transaction must not be swallowed as a duplicate "
                        + "of user A's")
                .isEqualTo(1);
        assertThat(countTransactionsInStatement(statementB)).isEqualTo(1);
    }

    // ADDED. REQ-STMT-02's Data Contracts section makes the index partial ("WHERE row_fingerprint
    // IS NOT NULL") precisely so "manual entries and bank-sync rows never set this column, so
    // they're correctly excluded from the uniqueness check". A NOT NULL column, or an index built
    // with NULLS NOT DISTINCT, would start rejecting the fingerprint-less rows every other feature
    // in the service writes.
    @Test
    @DisplayName("REQ-STMT-02: rows with no rowFingerprint are exempt from the uniqueness check "
            + "and all persist")
    void rowsWithoutAFingerprintAreExemptFromTheUniqueIndex() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var statementId = insertStatementAsSuperuser(accountId);
        UserContextHolder.set(userId);

        assertThat(transactionRepository.bulkInsertIgnoringDuplicates(statementId, List.of(
                buildStatementRow(accountId, statementId, new BigDecimal("-1.00"), null),
                buildStatementRow(accountId, statementId, new BigDecimal("-2.00"), null))))
                .isEqualTo(2);
        assertThat(countTransactionsInStatement(statementId)).isEqualTo(2);
    }

    // ADDED. Idempotency across calls is covered above; a retry is not the only way the same
    // fingerprint arrives twice. A statement can genuinely contain the same date/merchant/amount
    // twice, and REQ-STMT-02 mandates "one call per statement" — so both copies land inside a
    // single multi-row INSERT. This pins down that such a batch collapses to one row rather than
    // failing the whole statement, which is what a naive per-row insert loop or a DO UPDATE
    // conflict action would do instead.
    @Test
    @DisplayName("REQ-STMT-02: the same rowFingerprint appearing twice within one batch inserts "
            + "once, without failing the batch")
    void duplicateFingerprintsWithinTheSameBatchInsertOnce() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var statementId = insertStatementAsSuperuser(accountId);
        UserContextHolder.set(userId);

        int inserted = transactionRepository.bulkInsertIgnoringDuplicates(statementId, List.of(
                buildStatementRow(accountId, statementId, new BigDecimal("-3.00"), "5".repeat(64)),
                buildStatementRow(accountId, statementId, new BigDecimal("-3.00"), "5".repeat(64)),
                buildStatementRow(accountId, statementId, new BigDecimal("-4.00"), "6".repeat(64))));

        assertThat(inserted).isEqualTo(2);
        assertThat(countTransactionsInStatement(statementId)).isEqualTo(2);
    }

    private Transaction buildStatementRow(UUID accountId, UUID statementId, BigDecimal amount, String fingerprint) {
        return new Transaction(
                null, accountId, statementId, null, null,
                amount, "Test Merchant", "Groceries", null, List.of(),
                LocalDate.now(), Transaction.TransactionSource.STATEMENT_UPLOAD,
                Transaction.TransactionType.EXPENSE, Transaction.TransactionStatus.PENDING,
                false, false, null, fingerprint,
                Transaction.TransactionDirection.DEBIT, "USD", null, null);
    }

    private UUID insertStatementAsSuperuser(UUID accountId) throws SQLException {
        var statementId = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ledger.statements
                         (statement_id, account_id, s3_object_key, status, source_format,
                          opening_date, closing_date)
                     VALUES (?, ?, 'statements/x/y/z.csv', 'PROCESSING', 'CSV',
                             DATE '2026-08-03', DATE '2026-08-31')
                     """)) {
            ps.setObject(1, statementId);
            ps.setObject(2, accountId);
            ps.execute();
        }
        return statementId;
    }

    private Transaction buildChild(UUID parentId, UUID accountId, BigDecimal amount, String category,
                                    Transaction.TransactionStatus status) {
        return new Transaction(
                null, accountId, null, parentId, null,
                amount, "Test Merchant", category, "desc", List.of(),
                LocalDate.now(), Transaction.TransactionSource.MANUAL_ENTRY,
                Transaction.TransactionType.EXPENSE, status,
                false, false, null, null,
                Transaction.TransactionDirection.DEBIT, "USD", null, null);
    }

    private UUID insertPostedTransactionAsSuperuser(UUID accountId, BigDecimal amount) throws SQLException {
        var transactionId = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ledger.transactions
                         (transaction_id, account_id, amount, merchant, category, tx_date, source, type, direction, status)
                     VALUES (?, ?, ?, 'Test Merchant', 'Groceries', CURRENT_DATE, 'MANUAL_ENTRY', 'EXPENSE', 'DEBIT', 'POSTED')
                     """)) {
            ps.setObject(1, transactionId);
            ps.setObject(2, accountId);
            ps.setBigDecimal(3, amount);
            ps.execute();
        }
        return transactionId;
    }

    /**
     * Counts the rows currently visible for a statement, straight through the RLS-scoped
     * DSLContext. prompt.md introduces no transaction-counting repository method, so these
     * assertions query the table directly rather than inventing one — and going through the
     * RLS-scoped connection is what makes the "invisible to another tenant" assertions meaningful.
     */
    private int countTransactionsInStatement(UUID statementId) {
        return dsl.fetchCount(table("ledger.transactions"),
                field("statement_id", UUID.class).eq(statementId));
    }

    /** No user_id predicate at all — isolates RLS's own contribution from any app-layer filtering. */
    private List<UUID> fetchAllTransactionIdsWithNoFilter() {
        return dsl.select(field("transaction_id", UUID.class))
                .from(table("ledger.transactions"))
                .fetch(record -> record.get(0, UUID.class));
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

    private UUID insertTransactionAsSuperuser(UUID accountId, BigDecimal amount) throws SQLException {
        var transactionId = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ledger.transactions
                         (transaction_id, account_id, amount, merchant, category, tx_date, source, type, direction, status)
                     VALUES (?, ?, ?, 'Test Merchant', 'Groceries', CURRENT_DATE, 'MANUAL_ENTRY', 'EXPENSE', 'DEBIT', 'PENDING')
                     """)) {
            ps.setObject(1, transactionId);
            ps.setObject(2, accountId);
            ps.setBigDecimal(3, amount);
            ps.execute();
        }
        return transactionId;
    }
}
