package com.fintracker.ledger.category;

import com.fintracker.ledger.category.dto.CustomCategoryResponse;
import com.fintracker.ledger.category.model.Category;
import com.fintracker.ledger.category.repository.CategoryRepository;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DP-LEDGER-CATEGORIES-01: SYSTEM categories get an immutable, environment-stable {@code code};
 * {@code others} becomes {@code uncategorized} and {@code dining} becomes "Food &amp; Drink", both
 * keeping their UUIDs so existing transaction links survive.
 */
class CategoryCodeMigrationIT extends AbstractIntegrationTest {

    private static final String SYSTEM_SEED = "db/migration/R__System_Categories.sql";

    private static final Map<String, String> EXPECTED_CODE_BY_NAME = Map.ofEntries(
            Map.entry("groceries", "groceries"),
            Map.entry("food_&_drink", "food-and-drink"),
            Map.entry("transportation", "transportation"),
            Map.entry("shopping", "shopping"),
            Map.entry("entertainment", "entertainment"),
            Map.entry("utilities", "utilities"),
            Map.entry("housing", "housing"),
            Map.entry("healthcare", "healthcare"),
            Map.entry("insurance", "insurance"),
            Map.entry("subscriptions", "subscriptions"),
            Map.entry("travel", "travel"),
            Map.entry("education", "education"),
            Map.entry("personal_care", "personal-care"),
            Map.entry("income", "income"),
            Map.entry("transfer", "transfer"),
            Map.entry("fees", "fees"),
            Map.entry("uncategorized", "uncategorized"));

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    @DisplayName("every SYSTEM category has a code derived from its name (& becomes 'and', _ becomes -)")
    void everySystemCategoryHasADerivedCode() throws SQLException {
        assertThat(systemCodesByName(superuser())).isEqualTo(EXPECTED_CODE_BY_NAME);
    }

    @Test
    @DisplayName("a SYSTEM category's code cannot be changed after creation")
    void codeIsImmutable() throws SQLException {
        try (var conn = superuser(); var stmt = conn.createStatement()) {
            assertThatThrownBy(() -> stmt.executeUpdate(
                    "UPDATE ledger.categories SET code = 'groceries-2' WHERE code = 'groceries'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("a SYSTEM category's name stays editable while its code is unchanged")
    void nameStaysEditable() throws SQLException {
        try (var conn = superuser(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            stmt.executeUpdate("UPDATE ledger.categories SET category_name = 'grocery_shopping' WHERE code = 'groceries'");
            try (var rs = stmt.executeQuery("SELECT code FROM ledger.categories WHERE category_name = 'grocery_shopping'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("groceries");
            }
            conn.rollback();
        }
    }

    @Test
    @DisplayName("a USER category cannot carry a code")
    void userCategoryCannotHaveACode() throws SQLException {
        try (var conn = superuser(); var stmt = conn.createStatement()) {
            assertThatThrownBy(() -> stmt.executeUpdate("""
                    INSERT INTO ledger.categories (category_name, level, user_id, code)
                    VALUES ('side_hustle', 'USER', '%s', 'side-hustle')
                    """.formatted(UUID.randomUUID())))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("re-running the SYSTEM category seed creates no duplicates and keeps every UUID")
    void reRunningTheSeedIsIdempotent() throws SQLException, IOException {
        String seed = readResource(SYSTEM_SEED);
        try (var conn = superuser(); var stmt = conn.createStatement()) {
            var idsBefore = systemIdsByCode(conn);

            stmt.execute(seed);
            stmt.execute(seed);

            assertThat(systemIdsByCode(conn)).isEqualTo(idsBefore).hasSize(17);
        }
    }

    @Test
    @DisplayName("the repository exposes code and isActive, and food-and-drink displays as 'Food & Drink'")
    void repositoryExposesCodeAndActiveFlag() {
        var foodAndDrink = categoryRepository.findAllAccessibleToUser(UUID.randomUUID()).stream()
                .filter(c -> "food-and-drink".equals(c.code()))
                .findFirst().orElseThrow();

        assertThat(foodAndDrink.level()).isEqualTo(Category.Level.SYSTEM);
        assertThat(foodAndDrink.isActive()).isTrue();
        assertThat(CustomCategoryResponse.from(foodAndDrink).displayName()).isEqualTo("Food & Drink");
        assertThat(CustomCategoryResponse.from(foodAndDrink).code()).isEqualTo("food-and-drink");
    }

    @Test
    @DisplayName("upgrading an existing database keeps the others/dining UUIDs and renames their transaction text")
    void upgradeKeepsUuidsAndRenamesText() throws SQLException {
        String db = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = superuser(); var stmt = admin.createStatement()) {
            stmt.execute("CREATE DATABASE " + db);
        }
        String url = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), db);

        migrate(url, "20", false);
        UUID othersId;
        UUID diningId;
        try (var conn = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = conn.createStatement()) {
            othersId = systemIdByName(stmt, "others");
            diningId = systemIdByName(stmt, "dining");
            var accountId = UUID.randomUUID();
            stmt.execute("""
                    INSERT INTO ledger.accounts (account_id, user_id, account_name, account_type, sync_mode)
                    VALUES ('%s', '%s', 'Test Account', 'CHECKING', 'MANUAL')
                    """.formatted(accountId, UUID.randomUUID()));
            insertTransaction(stmt, accountId, "Others", othersId);
            insertTransaction(stmt, accountId, "Dining", diningId);
        }

        // Stop at V21 (with the category seed): later migrations assume rows in their own
        // shape, and pre-upgrade data is fixed by one-time scripts, not migrations.
        migrate(url, "21", true);

        try (var conn = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = conn.createStatement()) {
            assertThat(systemIdByCode(stmt, "uncategorized")).isEqualTo(othersId);
            assertThat(systemIdByCode(stmt, "food-and-drink")).isEqualTo(diningId);
            try (var rs = stmt.executeQuery("SELECT category FROM ledger.transactions ORDER BY category")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Food & Drink");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Uncategorized");
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void migrate(String url, String target, boolean withRepeatables) {
        var config = Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target);
        if (!withRepeatables) {
            // Flyway applies repeatable migrations on every migrate, even with a target; a real
            // database at V20 never runs R__System_Categories without V21, so keep them out here.
            config.repeatableSqlMigrationPrefix("__no_repeatables__");
        }
        config.load().migrate();
    }

    private static Map<String, String> systemCodesByName(Connection conn) throws SQLException {
        var result = new HashMap<String, String>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT category_name, code FROM ledger.categories WHERE level = 'SYSTEM'")) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2));
            }
        }
        return result;
    }

    private static Map<String, UUID> systemIdsByCode(Connection conn) throws SQLException {
        var result = new HashMap<String, UUID>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT code, category_id FROM ledger.categories WHERE level = 'SYSTEM'")) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getObject(2, UUID.class));
            }
        }
        return result;
    }

    private static UUID systemIdByName(Statement stmt, String name) throws SQLException {
        try (var rs = stmt.executeQuery(
                "SELECT category_id FROM ledger.categories WHERE level = 'SYSTEM' AND category_name = '" + name + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getObject(1, UUID.class);
        }
    }

    private static UUID systemIdByCode(Statement stmt, String code) throws SQLException {
        try (var rs = stmt.executeQuery(
                "SELECT category_id FROM ledger.categories WHERE level = 'SYSTEM' AND code = '" + code + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getObject(1, UUID.class);
        }
    }

    private static void insertTransaction(Statement stmt, UUID accountId, String category, UUID categoryId)
            throws SQLException {
        stmt.execute("""
                INSERT INTO ledger.transactions
                    (transaction_id, account_id, category_id, category, amount, merchant, tx_date, source, type, status)
                VALUES ('%s', '%s', '%s', '%s', -10.00, 'Test Merchant', CURRENT_DATE, 'MANUAL_ENTRY', 'PURCHASE', 'POSTED')
                """.formatted(UUID.randomUUID(), accountId, categoryId, category));
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = CategoryCodeMigrationIT.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("classpath resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
