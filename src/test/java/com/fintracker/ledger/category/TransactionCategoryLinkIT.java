package com.fintracker.ledger.category;

import com.fintracker.ledger.category.repository.CategoryRepository;
import com.fintracker.ledger.shared.UserContextHolder;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import com.fintracker.ledger.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FAIL-TO-PASS: REQ-TS-01 #7 — "the system shall reference categoryId" — and #6's reassignment
 * mechanics, exercised against the transactions table specifically. Deliberately narrow: this is
 * the boundary between the Category feature (fully covered in CategoryServiceTest /
 * CategoryControllerIT / JooqCategoryRepositoryIT) and the Transaction feature's own, much larger
 * existing test suite (TransactionServiceTest, 40 pre-existing cases).
 *
 * <p><b>Scope boundary — read before extending this file.</b> TransactionServiceTest.java is
 * regenerated from scratch by golden_tests/run_tests_f2p.sh on every run (see that script's own
 * header comment); anything added directly to it is discarded on the next run. The full ripple of
 * changing {@code Transaction.category} (String) to {@code Transaction.categoryId} (UUID) through
 * all 40 of that file's existing cases, {@code ManualTransactionRequest}, {@code
 * UpdateTransactionRequest}, and REQ-TS-01 item #8 (Budget feature matching by categoryId instead
 * of by string) is a substantially larger migration than this ticket's Category CRUD surface and
 * is intentionally NOT attempted here — see ledger-transaction-tests-01.md's "Known scope
 * boundary" section. These tests instead prove the two guarantees #7 explicitly promises, at the
 * schema/repository level, without depending on that larger DTO migration.
 *
 * <p>Because {@code Transaction.category} (String) is deliberately left untouched (see the scope
 * boundary above), assertions here read {@code transactions.category_id} back via a raw query
 * rather than through {@code TransactionRepository}/the {@code Transaction} domain record, which
 * does not expose that column.
 */
class TransactionCategoryLinkIT extends AbstractIntegrationTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    @DisplayName("#7: transactions.category_id is a real FK to categories — inserting a "
            + "transaction against a nonexistent categoryId is rejected by the database")
    void categoryIdForeignKeyIsEnforced() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var nonexistentCategoryId = UUID.randomUUID();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> insertTransactionAsSuperuser(accountId, nonexistentCategoryId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("#7: renaming a category is immediately visible on a transaction created before "
            + "the rename — no transaction row is touched, since the display name is resolved by "
            + "joining to categories at read time")
    void renameIsImmediatelyReflectedOnExistingTransactions() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        UserContextHolder.set(userId);
        var category = categoryRepository.insert(UUID.randomUUID(), "auto_tax", userId);
        var transactionId = insertTransactionAsSuperuser(accountId, category.categoryId());

        categoryRepository.update(category.categoryId(), "side_hustle");

        // The transaction row's category_id is untouched by the rename...
        assertThat(readCategoryIdOf(transactionId)).isEqualTo(category.categoryId());
        // ...but resolving that same id now returns the new name — the rename is visible
        // immediately because the display name is looked up at read time, not stored on the row.
        assertThat(categoryRepository.findByIdAndAccessibleToUser(category.categoryId(), userId)
                .orElseThrow().categoryName()).isEqualTo("side_hustle");
    }

    @Test
    @DisplayName("#6: reassignCategory moves every referencing transaction to the new category "
            + "and leaves other transactions untouched")
    void reassignCategoryMovesOnlyMatchingTransactions() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        UserContextHolder.set(userId);
        var commute = categoryRepository.insert(UUID.randomUUID(), "commute", userId);
        var groceries = categoryRepository.findAllAccessibleToUser(userId).stream()
                .filter(c -> "groceries".equals(c.categoryName())).findFirst().orElseThrow();
        var transportation = categoryRepository.findAllAccessibleToUser(userId).stream()
                .filter(c -> "transportation".equals(c.categoryName())).findFirst().orElseThrow();

        var movedTx = insertTransactionAsSuperuser(accountId, commute.categoryId());
        var untouchedTx = insertTransactionAsSuperuser(accountId, groceries.categoryId());

        transactionRepository.reassignCategory(commute.categoryId(), transportation.categoryId(), userId);

        assertThat(readCategoryIdOf(movedTx)).isEqualTo(transportation.categoryId());
        assertThat(readCategoryIdOf(untouchedTx)).isEqualTo(groceries.categoryId());
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

    private UUID insertTransactionAsSuperuser(UUID accountId, UUID categoryId) throws SQLException {
        var transactionId = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO ledger.transactions
                        (transaction_id, account_id, category_id, category, amount, merchant, tx_date, source, type, direction, status)
                    VALUES ('%s', '%s', '%s', 'other', -10.00, 'Test Merchant', CURRENT_DATE, 'MANUAL_ENTRY', 'EXPENSE', 'DEBIT', 'POSTED')
                    """.formatted(transactionId, accountId, categoryId));
        }
        return transactionId;
    }

    private UUID readCategoryIdOf(UUID transactionId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT category_id FROM ledger.transactions WHERE transaction_id = '%s'"
                             .formatted(transactionId))) {
            rs.next();
            return (UUID) rs.getObject("category_id");
        }
    }
}
