package com.fintracker.ledger.transaction.repository;

import com.fintracker.ledger.shared.UserContextHolder;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.model.TransactionFilter;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
                Transaction.TransactionType.PURCHASE, Transaction.TransactionStatus.PENDING,
                false, false, null);

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

        var filter = new TransactionFilter(userId, null, null, null, null, null, null, null, 0, 50);

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

    private Transaction buildChild(UUID parentId, UUID accountId, BigDecimal amount, String category,
                                    Transaction.TransactionStatus status) {
        return new Transaction(
                null, accountId, null, parentId, null,
                amount, "Test Merchant", category, "desc", List.of(),
                LocalDate.now(), Transaction.TransactionSource.MANUAL_ENTRY,
                Transaction.TransactionType.PURCHASE, status,
                false, false, null);
    }

    private UUID insertPostedTransactionAsSuperuser(UUID accountId, BigDecimal amount) throws SQLException {
        var transactionId = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ledger.transactions
                         (transaction_id, account_id, amount, merchant, category, tx_date, source, type, status)
                     VALUES (?, ?, ?, 'Test Merchant', 'Groceries', CURRENT_DATE, 'MANUAL_ENTRY', 'PURCHASE', 'POSTED')
                     """)) {
            ps.setObject(1, transactionId);
            ps.setObject(2, accountId);
            ps.setBigDecimal(3, amount);
            ps.execute();
        }
        return transactionId;
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
                         (transaction_id, account_id, amount, merchant, category, tx_date, source, type, status)
                     VALUES (?, ?, ?, 'Test Merchant', 'Groceries', CURRENT_DATE, 'MANUAL_ENTRY', 'PURCHASE', 'PENDING')
                     """)) {
            ps.setObject(1, transactionId);
            ps.setObject(2, accountId);
            ps.setBigDecimal(3, amount);
            ps.execute();
        }
        return transactionId;
    }
}
