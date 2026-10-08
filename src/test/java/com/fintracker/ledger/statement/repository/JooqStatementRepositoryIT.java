package com.fintracker.ledger.statement.repository;

import com.fintracker.ledger.shared.UserContextHolder;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-STMT-03/06/07: proves behavior a Mockito-mocked StatementRepository (see
 * StatementServiceTest) structurally cannot — real unique-index / RLS enforcement, and that
 * insert() actually round-trips the new opening_date/closing_date/content_hash columns.
 */
class JooqStatementRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private StatementRepository statementRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private org.jooq.DSLContext dsl;

    @Test
    @DisplayName("REQ-DP-05: the internal owner lookup works on a pooled connection that already served "
            + "a user request (app.current_user_id reset to '', not unset)")
    void ownerLookupWorksOnAConnectionReusedAfterAUserRequest() throws SQLException {
        var owner = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(owner);
        var statementId = insertStatementAsSuperuser(accountId, "9".repeat(64), LocalDate.of(2026, 8, 1));

        // One transaction pins one pooled connection: a user-scoped query sets and then RESETs
        // app.current_user_id on it (RlsExecuteListener), then the owner lookup runs on the same one.
        var found = transactionTemplate.execute(status -> {
            dsl.execute("SELECT set_config('app.current_user_id', ?, false)", UUID.randomUUID().toString());
            dsl.execute("RESET app.current_user_id");
            return statementRepository.findOwnerByStatementId(statementId);
        });

        assertThat(found).isPresent();
        assertThat(found.get().userId()).isEqualTo(owner);
    }

    @Test
    @DisplayName("RLS: a statement is invisible to another user, and visible to its owner")
    void rlsIsolatesStatementsAcrossTenants() throws SQLException {
        var userA = UUID.randomUUID();
        var userB = UUID.randomUUID();
        var accountA = insertAccountAsSuperuser(userA);
        var statementA = insertStatementAsSuperuser(accountA, "a".repeat(64), LocalDate.of(2026, 8, 1));

        UserContextHolder.set(userB);
        assertThat(statementRepository.findByIdAndUserId(statementA, userB)).isEmpty();

        UserContextHolder.set(userA);
        assertThat(statementRepository.findByIdAndUserId(statementA, userA)).isPresent();
    }

    @Test
    @DisplayName("REQ-STMT-03: findByAccountIdAndContentHash finds a real match, scoped to that account")
    void findByAccountIdAndContentHashFindsRealMatch() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var hash = "a".repeat(64);
        var statementId = insertStatementAsSuperuser(accountId, hash, LocalDate.of(2026, 8, 1));
        UserContextHolder.set(userId);

        var match = statementRepository.findByAccountIdAndContentHash(accountId, hash);

        assertThat(match).isPresent();
        assertThat(match.get().statementId()).isEqualTo(statementId);
        assertThat(match.get().contentHash()).isEqualTo(hash);
    }

    // Multi-tenant isolation at the query level: the same file's hash uploaded to two different
    // accounts must not be reported as a duplicate of each other — REQ-STMT-03's Constraints
    // section is explicit that this check is scoped per-account, not global.
    @Test
    @DisplayName("REQ-STMT-03: the same contentHash on a different account is NOT reported as a match")
    void contentHashMatchIsScopedToAccountNotGlobal() throws SQLException {
        var userA = UUID.randomUUID();
        var userB = UUID.randomUUID();
        var accountA = insertAccountAsSuperuser(userA);
        var accountB = insertAccountAsSuperuser(userB);
        var sharedHash = "b".repeat(64);
        insertStatementAsSuperuser(accountA, sharedHash, LocalDate.of(2026, 8, 1));

        UserContextHolder.set(userB);
        var match = statementRepository.findByAccountIdAndContentHash(accountB, sharedHash);

        assertThat(match).isEmpty();
    }

    @Test
    @DisplayName("REQ-STMT-06: findByAccountIdAndStatementMonth finds the account's existing "
            + "statement for that month")
    void findByAccountIdAndStatementMonthFindsRealMatch() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var month = LocalDate.of(2026, 8, 1);
        insertStatementAsSuperuser(accountId, "c".repeat(64), month);
        UserContextHolder.set(userId);

        var match = statementRepository.findByAccountIdAndStatementMonth(accountId, month);

        assertThat(match).isPresent();
    }

    @Test
    @DisplayName("REQ-STMT-07: insert() round-trips openingDate/closingDate, and the database "
            + "derives statement_month from closingDate on its own")
    void insertRoundTripsNewColumns() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        UserContextHolder.set(userId);

        var statementId = UUID.randomUUID();
        var opening = LocalDate.of(2026, 8, 3);
        var closing = LocalDate.of(2026, 9, 2);
        var month = closing.withDayOfMonth(1);

        // Signature per prompt.md REQ-STMT-07: statementId, accountId, s3ObjectKey, openingDate,
        // closingDate, contentHash, description, sourceFormat, bankId — statementMonth is NOT a
        // parameter, so this call compiles only against the new signature.
        var hash = "d".repeat(64);
        var created = statementRepository.insert(statementId, accountId, "statements/x/y/z.csv",
                opening, closing, hash, "August statement", "CSV", "chase");

        assertThat(created.openingDate()).isEqualTo(opening);
        assertThat(created.closingDate()).isEqualTo(closing);
        assertThat(created.contentHash()).isEqualTo(hash);
        // Aug 3 - Sep 2 groups under September. Nothing in application code computed this: the
        // generated column did, which is the whole point of REQ-STMT-07 moving the derivation into
        // the database ("structurally impossible for statement_month to disagree with closing_date").
        assertThat(created.statementMonth()).isEqualTo(month);

        var reloaded = statementRepository.findByIdAndUserId(statementId, userId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().openingDate()).isEqualTo(opening);
        assertThat(reloaded.get().closingDate()).isEqualTo(closing);
        assertThat(reloaded.get().contentHash()).isEqualTo(hash);
    }

    // Much more than a sanity check after REQ-STMT-07: V16 DROPS the statement_month column to
    // convert it to GENERATED, which silently takes idx_unique_account_statement_month
    // (V1__Initial_Schema.sql:33-34) with it, and the migration has to recreate it by hand. If that
    // recreation is forgotten, every REQ-STMT-06 guarantee quietly degrades from "enforced by the
    // database" to "enforced by an application pre-check that a race can slip past" — with no
    // error anywhere to notice. This is the only test that would catch it.
    @Test
    @DisplayName("REQ-STMT-06/07: the DB-level unique index on (account_id, statement_month) "
            + "survives V16's column conversion and still rejects a second statement for the same "
            + "account+month when the application pre-check is bypassed")
    void uniqueIndexStillRejectsDuplicateAccountMonthAtDbLevel() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var month = LocalDate.of(2026, 8, 1);
        insertStatementAsSuperuser(accountId, "e".repeat(64), month);
        UserContextHolder.set(userId);

        // A different file with a different declared range whose closing date still falls in
        // August, so the generated statement_month collides with the fixture's.
        assertThatThrownBy(() -> statementRepository.insert(
                UUID.randomUUID(), accountId, "statements/other.csv",
                LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 30), "f".repeat(64),
                "desc", "CSV", "chase"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ADDED. The content-hash lookup already has a per-account scoping test above; the month
    // lookup — REQ-STMT-06's query — had none. REQ-STMT-06 is an account-level rule ("an account
    // cannot have two statements for the same calendar month"), so a query that dropped the
    // account_id predicate would start telling every user their month is already taken as soon as
    // any other user uploaded a statement for it. That is both a false rejection and a disclosure
    // that some other account holds a statement for that month.
    @Test
    @DisplayName("REQ-STMT-06 multi-tenant: another account's statement for the same month is NOT "
            + "reported as a match")
    void statementMonthMatchIsScopedToAccountNotGlobal() throws SQLException {
        var userA = UUID.randomUUID();
        var userB = UUID.randomUUID();
        var accountA = insertAccountAsSuperuser(userA);
        var accountB = insertAccountAsSuperuser(userB);
        var month = LocalDate.of(2026, 8, 1);
        insertStatementAsSuperuser(accountA, "1".repeat(64), month);

        UserContextHolder.set(userB);

        assertThat(statementRepository.findByAccountIdAndStatementMonth(accountB, month)).isEmpty();
    }

    // ADDED. The sharpest multi-tenant case for REQ-STMT-03's internal duplicate-check endpoint:
    // accountId arrives there as a raw query parameter, so the attacker model is a caller who has
    // the internal API key and simply supplies the victim's real accountId instead of one of their
    // own. StatementServiceTest proves the service-layer ownership guard rejects that; this proves
    // the database is the second line of defense — the RLS policy filters the row out on the way
    // back, so even a service-layer guard that regressed cannot leak existingStatementId,
    // existingUploadDate or existingTransactionCount for a statement the caller does not own.
    @Test
    @DisplayName("multi-tenant: duplicate lookups made with another tenant's real accountId return "
            + "nothing — RLS filters the row regardless of the accountId passed in")
    void duplicateLookupsWithAnotherTenantsAccountIdReturnNothing() throws SQLException {
        var victim = UUID.randomUUID();
        var attacker = UUID.randomUUID();
        var victimAccount = insertAccountAsSuperuser(victim);
        var month = LocalDate.of(2026, 8, 1);
        var hash = "2".repeat(64);
        insertStatementAsSuperuser(victimAccount, hash, month);

        // The attacker is a legitimate, authenticated tenant — they just guessed/leaked an
        // accountId that is not theirs.
        UserContextHolder.set(attacker);

        assertThat(statementRepository.findByAccountIdAndContentHash(victimAccount, hash)).isEmpty();
        assertThat(statementRepository.findByAccountIdAndStatementMonth(victimAccount, month)).isEmpty();

        // Sanity: the row genuinely exists — the emptiness above is isolation, not a bad fixture.
        UserContextHolder.set(victim);
        assertThat(statementRepository.findByAccountIdAndContentHash(victimAccount, hash)).isPresent();
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

    /**
     * REQ-STMT-07 turns statement_month into a GENERATED column, so it cannot be written directly
     * even as the superuser — the fixture sets a closing_date inside the intended month and lets
     * the database derive the rest, exactly as the application now does.
     */
    private UUID insertStatementAsSuperuser(UUID accountId, String contentHash, LocalDate statementMonth)
            throws SQLException {
        var statementId = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ledger.statements
                         (statement_id, account_id, s3_object_key, status,
                          source_format, content_hash, opening_date, closing_date)
                     VALUES (?, ?, 'statements/x/y/z.csv', 'COMPLETED', 'CSV', ?, ?, ?)
                     """)) {
            ps.setObject(1, statementId);
            ps.setObject(2, accountId);
            ps.setString(3, contentHash);
            ps.setObject(4, statementMonth.withDayOfMonth(3));
            ps.setObject(5, statementMonth.withDayOfMonth(28));
            ps.execute();
        }
        return statementId;
    }
}
