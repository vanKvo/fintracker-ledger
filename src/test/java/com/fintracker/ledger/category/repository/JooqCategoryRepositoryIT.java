package com.fintracker.ledger.category.repository;

import com.fintracker.ledger.category.model.Category;
import com.fintracker.ledger.shared.UserContextHolder;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FAIL-TO-PASS: proves behavior a Mockito-mocked CategoryRepository (see CategoryServiceTest)
 * structurally cannot — real unique-index enforcement, RLS-equivalent tenant isolation, the
 * migration's seed data, and the ON DELETE RESTRICT guarantee backing REQ-TS-01 #6 at the
 * database layer as well as the service layer.
 *
 * <p>Requires the migration described in ledger-transaction-spec-01.md's Data Contracts:
 * a new {@code ledger.categories} table seeded with the 17 {@code TransactionCategory} enum
 * values as SYSTEM rows, and {@code ledger.transactions.category_id} replacing the old
 * {@code category} string column.
 */
class JooqCategoryRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    @DisplayName("migration seed: exactly 17 SYSTEM-level categories exist, including GROCERIES and UNCATEGORIZED")
    void migrationSeedsTheSeventeenSystemCategories() {
        var systemCategories = categoryRepository.findAllAccessibleToUser(UUID.randomUUID()).stream()
                .filter(c -> c.level() == Category.Level.SYSTEM)
                .toList();

        assertThat(systemCategories).hasSize(17);
        assertThat(systemCategories).extracting(Category::categoryName)
                .contains("groceries", "uncategorized");
        assertThat(systemCategories).allMatch(c -> c.userId() == null);
    }

    @Test
    @DisplayName("unique index rejects a duplicate (user_id, category_name) for USER-level rows")
    void uniqueIndexRejectsDuplicateUserCategory() {
        var userId = UUID.randomUUID();
        UserContextHolder.set(userId);
        categoryRepository.insert(UUID.randomUUID(), "auto_tax", userId);

        assertThatThrownBy(() -> categoryRepository.insert(UUID.randomUUID(), "auto_tax", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the same normalized name is permitted for two different users at the DB level")
    void uniqueIndexIsScopedPerUser() {
        var userA = UUID.randomUUID();
        var userB = UUID.randomUUID();

        UserContextHolder.set(userA);
        assertThat(categoryRepository.insert(UUID.randomUUID(), "auto_tax", userA)).isNotNull();
        UserContextHolder.set(userB);
        assertThat(categoryRepository.insert(UUID.randomUUID(), "auto_tax", userB)).isNotNull();
    }

    @Test
    @DisplayName("a USER-level category is invisible to another user")
    void userCategoryIsIsolatedFromOtherUsers() {
        var owner = UUID.randomUUID();
        var otherUser = UUID.randomUUID();
        UserContextHolder.set(owner);
        var created = categoryRepository.insert(UUID.randomUUID(), "auto_tax", owner);

        UserContextHolder.set(otherUser);
        assertThat(categoryRepository.findByIdAndAccessibleToUser(created.categoryId(), otherUser)).isEmpty();

        UserContextHolder.set(owner);
        assertThat(categoryRepository.findByIdAndAccessibleToUser(created.categoryId(), owner)).isPresent();
    }

    @Test
    @DisplayName("a SYSTEM-level category is accessible to every user")
    void systemCategoryIsAccessibleToEveryUser() {
        var anyUser = UUID.randomUUID();
        var groceries = categoryRepository.findAllAccessibleToUser(anyUser).stream()
                .filter(c -> "groceries".equals(c.categoryName()))
                .findFirst().orElseThrow();

        assertThat(categoryRepository.findByIdAndAccessibleToUser(groceries.categoryId(), anyUser)).isPresent();
    }

    @Test
    @DisplayName("countByUserId counts only that user's own custom categories, not SYSTEM rows")
    void countByUserIdExcludesSystemCategories() {
        var userId = UUID.randomUUID();
        UserContextHolder.set(userId);
        categoryRepository.insert(UUID.randomUUID(), "auto_tax", userId);
        categoryRepository.insert(UUID.randomUUID(), "side_hustle", userId);

        assertThat(categoryRepository.countByUserId(userId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("#7: renaming via update() preserves categoryId, changing only category_name")
    void updatePreservesCategoryId() {
        var userId = UUID.randomUUID();
        UserContextHolder.set(userId);
        var created = categoryRepository.insert(UUID.randomUUID(), "auto_tax", userId);

        var updated = categoryRepository.update(created.categoryId(), "side_hustle");

        assertThat(updated.categoryId()).isEqualTo(created.categoryId());
        assertThat(updated.categoryName()).isEqualTo("side_hustle");
    }

    @Test
    @DisplayName("#6: ON DELETE RESTRICT — a category referenced by a transaction cannot be "
            + "hard-deleted at the database level")
    void onDeleteRestrictPreventsDeletingAnInUseCategory() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        UserContextHolder.set(userId);
        var created = categoryRepository.insert(UUID.randomUUID(), "commute", userId);
        insertTransactionReferencingCategoryAsSuperuser(accountId, created.categoryId());

        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            assertThatThrownBy(() -> stmt.execute(
                    "DELETE FROM ledger.categories WHERE category_id = '" + created.categoryId() + "'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("DP-LEDGER-CATEGORIES-02: a deactivated category leaves the list and name/cap checks, "
            + "but stays findable by id")
    void deactivateHidesCategoryButKeepsIt() {
        var userId = UUID.randomUUID();
        UserContextHolder.set(userId);
        var created = categoryRepository.insert(UUID.randomUUID(), "one_off", userId);

        categoryRepository.deactivate(created.categoryId());

        assertThat(categoryRepository.findAllAccessibleToUser(userId))
                .extracting(Category::categoryId).doesNotContain(created.categoryId());
        assertThat(categoryRepository.existsByNormalizedNameAccessibleToUser("one_off", userId)).isFalse();
        assertThat(categoryRepository.countByUserId(userId)).isZero();
        assertThat(categoryRepository.findByIdAndAccessibleToUser(created.categoryId(), userId))
                .get().extracting(Category::isActive).isEqualTo(false);
        assertThat(categoryRepository.findInactiveUserCategoryByName("one_off", userId))
                .get().extracting(Category::categoryId).isEqualTo(created.categoryId());
    }

    @Test
    @DisplayName("DP-LEDGER-CATEGORIES-02: reactivate brings a category back under the same id")
    void reactivateRestoresTheCategory() {
        var userId = UUID.randomUUID();
        UserContextHolder.set(userId);
        var created = categoryRepository.insert(UUID.randomUUID(), "one_off", userId);
        categoryRepository.deactivate(created.categoryId());

        var reactivated = categoryRepository.reactivate(created.categoryId());

        assertThat(reactivated.categoryId()).isEqualTo(created.categoryId());
        assertThat(reactivated.isActive()).isTrue();
        assertThat(categoryRepository.findAllAccessibleToUser(userId))
                .extracting(Category::categoryId).contains(created.categoryId());
    }

    // --- fixture helpers -------------------------------------------------------------------
    // Mirrors AbstractIntegrationTest's own insertAccountAsSuperuser pattern (see
    // JooqStatementRepositoryIT for the precedent) — DDL-adjacent fixture setup runs as the
    // Testcontainers superuser, bypassing RLS, since it is arranging state rather than
    // exercising the behavior under test.

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

    private void insertTransactionReferencingCategoryAsSuperuser(UUID accountId, UUID categoryId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO ledger.transactions
                        (transaction_id, account_id, category_id, category, amount, merchant, tx_date, source, type, direction, status)
                    VALUES ('%s', '%s', '%s', 'other', -10.00, 'Test Merchant', CURRENT_DATE, 'MANUAL_ENTRY', 'EXPENSE', 'DEBIT', 'POSTED')
                    """.formatted(UUID.randomUUID(), accountId, categoryId));
        }
    }
}
