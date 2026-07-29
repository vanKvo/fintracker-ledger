package com.fintracker.ledger.testsupport;

import com.fintracker.ledger.shared.UserContextHolder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Base class for Testcontainers-backed integration tests that need a real Postgres — the things a
 * Mockito-mocked repository structurally cannot verify: actual SQL/constraint behavior, and Row
 * Level Security (RLS) enforcement.
 *
 * <p><b>Why this isn't just "start a container and point the app at it":</b> Testcontainers'
 * default Postgres bootstrap user (whatever {@code POSTGRES_USER} is set to — here, the container's
 * default {@code test} user) is a Postgres <i>superuser</i>. Superusers unconditionally bypass Row
 * Level Security, regardless of {@code FORCE ROW LEVEL SECURITY}. An integration test that runs
 * entirely as that default user would "pass" an RLS test without RLS ever actually being enforced —
 * a subtle, easy-to-miss way to write a test that verifies nothing. To test RLS for real, the
 * application's own connection must run as a genuinely restricted, non-superuser role.
 *
 * <p>Sequencing this correctly means: migrate the schema as the superuser (DDL needs elevated
 * privileges anyway), then create a least-privilege {@code app_user} role and grant it exactly what
 * the running application needs, then point the Spring context's {@code spring.datasource.*} at
 * that restricted role — all of which must happen <i>before</i> the Spring context boots, since
 * Spring Boot's own Flyway auto-configuration would otherwise try to run migrations as {@code
 * app_user}, which has no DDL privileges.
 *
 * <p>Uses Testcontainers' documented "singleton container" pattern (start once in a static
 * initializer, never call {@code stop()} — Testcontainers' Ryuk reaper cleans it up when the JVM
 * exits) so the container is shared across every integration test class in the same run, not
 * restarted per class.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_password";

    // Pinned to 16, matching what this service's own docker-compose.yml (and CLAUDE.md) historically
    // targeted, and squarely within the resolved Flyway version's (10.20.1, via the Spring Boot 3.4.4
    // BOM) documented compatibility ceiling of Postgres 17 — deliberately not the newer version some
    // shared local dev Postgres instances run, to avoid introducing another version-mismatch variable
    // into what these tests are actually trying to prove.
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintracker_it");

    static {
        POSTGRES.start();
        migrateAsSuperuserThenProvisionAppRole();
    }

    private static void migrateAsSuperuserThenProvisionAppRole() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
            stmt.execute("GRANT USAGE ON SCHEMA ledger TO " + APP_USER);
            stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ledger TO " + APP_USER);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to provision the restricted app_user role "
                    + "needed for RLS to be meaningfully testable", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Deliberately app_user, not POSTGRES.getUsername() — see class Javadoc.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        // Migrations already ran (as superuser) in the static initializer above; prevent Spring
        // Boot's own Flyway auto-configuration from also trying to run them as app_user, which has
        // no DDL privileges.
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @AfterEach
    void clearUserContext() {
        // RlsExecuteListener reads this per-query; leaking a value across test methods would make
        // one test's RLS session identity bleed into the next.
        UserContextHolder.clear();
    }
}
