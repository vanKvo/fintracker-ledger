package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.service.BudgetService;
import com.fintracker.ledger.shared.UserContextHolder;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import com.fintracker.ledger.testsupport.MutableTestClock;
import com.fintracker.ledger.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared fixtures for the REQ-5.1 suite.
 *
 * <p><b>Why every REQ-5.1 test is integration-level rather than a Mockito unit test.</b> REQ-5.1
 * pins behavior — what status a budget ends up in, what a rejected payload leaves behind,
 * what a category's spend sums to — and says nothing about how {@code BudgetService} talks to its
 * repository. Asserting through a mocked {@code BudgetRepository} would freeze that private
 * collaboration (which methods get called, in what order) into the test suite, so a correct
 * implementation that restructures the repository interface would fail tests for no behavioral
 * reason. Driving the real service against a real Postgres keeps the assertions on observable
 * outcomes only, and is the only way to verify the constraint/RLS half of the requirement at all.
 *
 * <p>Fixture rows are written as the Postgres superuser (bypassing RLS — this is setup, not the
 * thing under test) while assertions run through the Spring-managed beans on the restricted
 * {@code app_user} connection. See {@link AbstractIntegrationTest} for why that split matters.
 */
@Import(TestClockConfig.class)
public abstract class AbstractBudgetIT extends AbstractIntegrationTest {

    @Autowired
    protected BudgetService budgetService;

    @Autowired
    protected MutableTestClock clock;

    /** A fresh tenant per test — no test can be perturbed by another's rows. */
    protected UUID userId;

    @BeforeEach
    void resetClockAndTenant() {
        clock.setTo(TestClockConfig.DEFAULT_NOW);
        userId = UUID.randomUUID();
        UserContextHolder.set(userId);
    }

    /** Switches the ambient tenant, so RLS and application scoping both follow. */
    protected void actAs(UUID otherUserId) {
        UserContextHolder.set(otherUserId);
    }

    // ---------------------------------------------------------------- periods

    protected LocalDate currentMonth() {
        return clock.currentMonth();
    }

    protected LocalDate pastMonth() {
        return clock.currentMonth().minusMonths(3);
    }

    protected LocalDate futureMonth() {
        return clock.currentMonth().plusMonths(3);
    }

    // ------------------------------------------------------------ line builders

    protected static BudgetLine line(String category, String limitAmount) {
        return new BudgetLine(null, null, category, new BigDecimal(limitAmount), null, null);
    }

    /** {@code count} distinct, individually valid lines — for exercising the 50-item ceiling. */
    protected static List<BudgetLine> distinctLines(int count) {
        var result = new ArrayList<BudgetLine>(count);
        for (int i = 0; i < count; i++) {
            result.add(line("Category-" + i, "10.00"));
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------- transaction fixtures

    /** An account is required because transactions hang off one and derive user_id from it. */
    protected UUID insertAccount(UUID owner) {
        var accountId = UUID.randomUUID();
        executeAsSuperuser("""
                INSERT INTO ledger.accounts (account_id, user_id, account_name, account_type, sync_mode)
                VALUES (?, ?, 'Test Account', 'CHECKING', 'MANUAL')
                """, accountId, owner);
        return accountId;
    }

    /** A POSTED PURCHASE — the only shape REQ-5.1 counts as an "approved" expense. */
    protected UUID insertPostedPurchase(UUID accountId, String category, String amount, LocalDate date) {
        return insertTransaction(accountId, category, amount, date, "PURCHASE", "POSTED", false, null);
    }

    protected UUID insertTransaction(UUID accountId, String category, String amount, LocalDate date,
                                     String type, String status, boolean excluded, UUID parentId) {
        var transactionId = UUID.randomUUID();
        executeAsSuperuser("""
                INSERT INTO ledger.transactions
                    (transaction_id, account_id, parent_transaction_id, amount, merchant, category,
                     tx_date, source, type, status, is_excluded)
                VALUES (?, ?, ?, ?, 'Test Merchant', ?, ?, 'MANUAL_ENTRY', ?, ?, ?)
                """, transactionId, accountId, parentId, new BigDecimal(amount), category, date,
                type, status, excluded);
        return transactionId;
    }

    // ------------------------------------------------- direct persistence readback
    //
    // Read back with a superuser connection and raw SQL rather than through the repository, so a
    // bug in the read path cannot mask a bug in the write path.

    protected List<Map<String, Object>> readLineRows(UUID budgetId) {
        return queryAsSuperuser(
                "SELECT category, limit_amount FROM ledger.budget_lines WHERE budget_id = ? ORDER BY category",
                budgetId);
    }

    protected int countBudgetRows(UUID owner) {
        var rows = queryAsSuperuser("SELECT COUNT(*) AS c FROM ledger.budgets WHERE user_id = ?", owner);
        return ((Number) rows.get(0).get("c")).intValue();
    }

    protected int countLineRows(UUID budgetId) {
        var rows = queryAsSuperuser("SELECT COUNT(*) AS c FROM ledger.budget_lines WHERE budget_id = ?", budgetId);
        return ((Number) rows.get(0).get("c")).intValue();
    }

    /**
     * Reads {@code ledger.budgets.status} directly. Until the REQ-5.1 migration adds the column
     * this throws {@code column "status" does not exist} — which is the point: the column is part
     * of the requirement (REQ-5.1 C. Data Impacts), not an assumption of the test harness.
     */
    protected String readStatusColumn(UUID budgetId) {
        var rows = queryAsSuperuser("SELECT status FROM ledger.budgets WHERE budget_id = ?", budgetId);
        return rows.isEmpty() ? null : (String) rows.get(0).get("status");
    }

    // ------------------------------------------------------------------ plumbing

    protected void executeAsSuperuser(String sql, Object... params) {
        try (Connection conn = superuserConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("Fixture SQL failed: " + sql, e);
        }
    }

    protected List<Map<String, Object>> queryAsSuperuser(String sql, Object... params) {
        try (Connection conn = superuserConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                var meta = rs.getMetaData();
                var rows = new ArrayList<Map<String, Object>>();
                while (rs.next()) {
                    var row = new java.util.LinkedHashMap<String, Object>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Fixture query failed: " + sql, e);
        }
    }

    private static Connection superuserConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
