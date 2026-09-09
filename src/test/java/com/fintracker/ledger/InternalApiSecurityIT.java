package com.fintracker.ledger;

import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-STMT-02/03: the internal service-to-service routes, exercised through MockMvc with the
 * real filter chain and a real Postgres — the security boundary is the point of these tests,
 * so nothing below the filters is mocked away:
 *
 *   1. Every route under {@code /api/v1/ledger/**&#47;internal/**} requires BOTH the edge-verified
 *      caller ARN ({@code X-Internal-Caller-Arn}, on the deployed allow-list) AND an internal
 *      user id ({@code X-Internal-User-Id}); either one missing or wrong is a 401, answered by
 *      a filter before any controller method runs.
 *   2. The bulk-create flow works end to end for a legitimate caller, and its idempotency key
 *      ({@code row_fingerprint}) makes a dispatcher retry a safe no-op at HTTP level too —
 *      the retried response reports the rows as skipped duplicates, not errors.
 *   3. The internal duplicate check answers the Gatekeeper's question against live data,
 *      is scoped by the {@code X-Internal-User-Id} (another tenant's account is a 400, not
 *      an oracle), and rejects ambiguous calls (both/neither hash parameter) with 400.
 *
 * The allow-list value used here is application.yml's local-dev default; the assertions are
 * about listed-vs-unlisted, not about any particular production ARN.
 */
@AutoConfigureMockMvc
class InternalApiSecurityIT extends AbstractIntegrationTest {

    private static final String BULK = "/api/v1/ledger/transactions/internal/bulk";
    private static final String DUPLICATE_CHECK = "/api/v1/ledger/statements/internal/duplicate-check";
    private static final String CALLER_ARN_HEADER = "X-Internal-Caller-Arn";
    private static final String USER_HEADER = "X-Internal-User-Id";
    private static final String ALLOWED_ARN = "arn:aws:iam::000000000000:role/local-dev-dispatcher";
    private static final String FOREIGN_ARN = "arn:aws:iam::999999999999:role/some-other-service";

    @Autowired
    private MockMvc mockMvc;

    // ---- The security boundary: both identities required on every internal call ----

    @Test
    @DisplayName("an internal call without a caller ARN is 401, answered before the controller runs")
    void missingCallerArnIs401() throws Exception {
        mockMvc.perform(post(BULK)
                        .header(USER_HEADER, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkPayload(UUID.randomUUID(), 1, 0)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(endsWith("/problems/missing-internal-caller")));
    }

    @Test
    @DisplayName("an internal call with an ARN not on the allow-list is 401")
    void unlistedCallerArnIs401() throws Exception {
        mockMvc.perform(post(BULK)
                        .header(CALLER_ARN_HEADER, FOREIGN_ARN)
                        .header(USER_HEADER, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkPayload(UUID.randomUUID(), 1, 0)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(endsWith("/problems/internal-caller-not-allowed")));
    }

    @Test
    @DisplayName("a verified caller ARN alone is not enough: without X-Internal-User-Id the call is 401")
    void missingUserHeaderIs401() throws Exception {
        mockMvc.perform(post(BULK)
                        .header(CALLER_ARN_HEADER, ALLOWED_ARN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkPayload(UUID.randomUUID(), 1, 0)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(endsWith("/problems/missing-user-identity")));
    }

    // ---- REQ-STMT-02: the bulk-create flow itself ----

    @Test
    @DisplayName("a legitimate bulk call inserts the statement's rows; a retry of the same "
            + "batch is a safe no-op reported as skipped duplicates, not an error")
    void bulkCreateIsIdempotentAcrossRetries() throws Exception {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var statementId = insertStatementAsSuperuser(accountId);
        var body = bulkPayload(statementId, 2, 0);

        mockMvc.perform(post(BULK)
                        .header(CALLER_ARN_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insertedCount").value(2))
                .andExpect(jsonPath("$.skippedDuplicateCount").value(0))
                .andExpect(jsonPath("$.failedRows").isEmpty());

        mockMvc.perform(post(BULK)
                        .header(CALLER_ARN_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insertedCount").value(0))
                .andExpect(jsonPath("$.skippedDuplicateCount").value(2))
                .andExpect(jsonPath("$.failedRows").isEmpty());

        assertThat(countTransactions(statementId)).isEqualTo(2);
    }

    @Test
    @DisplayName("bulk-creating into a statement owned by another user is 404 — the internal "
            + "caller is not an oracle for other tenants' statement IDs")
    void bulkCreateIntoAnotherUsersStatementIs404() throws Exception {
        var foreignStatementId = insertStatementAsSuperuser(insertAccountAsSuperuser(UUID.randomUUID()));

        mockMvc.perform(post(BULK)
                        .header(CALLER_ARN_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkPayload(foreignStatementId, 1, 0)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("structurally malformed rows are reported per-row in a 200, never an aborted batch")
    void malformedRowsAreReportedPerRow() throws Exception {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var statementId = insertStatementAsSuperuser(accountId);

        // Row 0: fine. Row 1: merchant blank. Row 2: exactly-zero amount (matches the
        // DB's CHECK (amount != 0), enforced per row before the insert is built).
        var body = """
                {"statementId": "%s", "transactions": [
                  {"date": "2026-08-15", "merchant": "Groceries", "amount": -25.00,
                   "category": "Groceries", "type": "PURCHASE", "rowFingerprint": "%s"},
                  {"date": "2026-08-16", "merchant": "  ", "amount": -9.99,
                   "category": "Dining", "type": "PURCHASE", "rowFingerprint": "%s"},
                  {"date": "2026-08-17", "merchant": "Zero Co", "amount": 0.00,
                   "category": "Misc", "type": "PURCHASE", "rowFingerprint": "%s"}
                ]}
                """.formatted(statementId, "1".repeat(64), "2".repeat(64), "3".repeat(64));

        mockMvc.perform(post(BULK)
                        .header(CALLER_ARN_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insertedCount").value(1))
                .andExpect(jsonPath("$.failedRows.length()").value(2))
                .andExpect(jsonPath("$.failedRows[0].index").value(1))
                .andExpect(jsonPath("$.failedRows[1].index").value(2))
                .andExpect(jsonPath("$.failedRows[1].reason").value("amount must not be zero"));
    }

    @Test
    @DisplayName("a batch over the 10,000-row cap is rejected 400 before any parsing of the "
            + "rows themselves — the request size is bounded, not just chunked")
    void batchOverTheRowCapIs400() throws Exception {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var statementId = insertStatementAsSuperuser(accountId);

        mockMvc.perform(post(BULK)
                        .header(CALLER_ARN_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkPayload(statementId, 10_001, 0)))
                .andExpect(status().isBadRequest());
    }

    // ---- REQ-STMT-03: the internal duplicate check ----

    @Test
    @DisplayName("duplicate-check with no prior upload answers duplicateFound=false")
    void duplicateCheckWithNoMatchIs200False() throws Exception {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);

        mockMvc.perform(get(DUPLICATE_CHECK)
                        .header(CALLER_ARN_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, userId)
                        .param("accountId", accountId.toString())
                        .param("contentHash", "9".repeat(64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateFound").value(false));
    }

    @Test
    @DisplayName("duplicate-check for an already-uploaded file answers EXACT_FILE with the "
            + "existing statement's identity")
    void duplicateCheckWithExactMatchIs200True() throws Exception {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);
        var statementId = insertStatementAsSuperuser(accountId);

        mockMvc.perform(get(DUPLICATE_CHECK)
                        .header(CALLER_ARN_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, userId)
                        .param("accountId", accountId.toString())
                        .param("contentHash", "e".repeat(64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateFound").value(true))
                .andExpect(jsonPath("$.matchType").value("EXACT_FILE"))
                .andExpect(jsonPath("$.existingStatementId").value(statementId.toString()))
                .andExpect(jsonPath("$.existingUploadDate").isNotEmpty())
                .andExpect(jsonPath("$.existingTransactionCount").value(0));
    }

    @Test
    @DisplayName("duplicate-check with both hash parameters is 400 — one question per call")
    void duplicateCheckWithBothParamsIs400() throws Exception {
        // An account the caller actually owns, so the 400 provably comes from the
        // mutual-exclusion rule and not the ownership guard that runs before it.
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);

        mockMvc.perform(get(DUPLICATE_CHECK)
                        .header(CALLER_ARN_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, userId)
                        .param("accountId", accountId.toString())
                        .param("contentHash", "9".repeat(64))
                        .param("contentFingerprint", "8".repeat(64)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("duplicate-check with neither hash parameter is 400")
    void duplicateCheckWithNeitherParamIs400() throws Exception {
        var userId = UUID.randomUUID();
        var accountId = insertAccountAsSuperuser(userId);

        mockMvc.perform(get(DUPLICATE_CHECK)
                        .header(CALLER_ARN_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, userId)
                        .param("accountId", accountId.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("duplicate-check against another tenant's account is 400, not an existence oracle")
    void duplicateCheckForAnotherUsersAccountIs400() throws Exception {
        var foreignAccountId = insertAccountAsSuperuser(UUID.randomUUID());

        mockMvc.perform(get(DUPLICATE_CHECK)
                        .header(CALLER_ARN_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, UUID.randomUUID())
                        .param("accountId", foreignAccountId.toString())
                        .param("contentHash", "9".repeat(64)))
                .andExpect(status().isBadRequest());
    }

    // ---- helpers ----

    /** {@code lineCount} well-formed transaction lines, fingerprints starting from {@code seed}. */
    private String bulkPayload(UUID statementId, int lineCount, int seed) {
        var lines = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            if (i > 0) {
                lines.append(',');
            }
            lines.append("""
                    {"date": "2026-08-15", "merchant": "Merchant %d", "amount": -25.00,
                     "category": "Groceries", "type": "PURCHASE", "rowFingerprint": "%s"}
                    """.formatted(seed + i, fingerprint(seed + i)));
        }
        return "{\"statementId\": \"%s\", \"transactions\": [%s]}".formatted(statementId, lines);
    }

    /** A deterministic 64-char lowercase-hex fingerprint for test row {@code n}. */
    private static String fingerprint(int n) {
        return "%064x".formatted(n + 1);
    }

    private int countTransactions(UUID statementId) throws SQLException {
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
                     VALUES (?, ?, ?, 'COMPLETED', '2026-08-03', '2026-09-02', ?)
                     """)) {
            ps.setObject(1, statementId);
            ps.setObject(2, accountId);
            ps.setObject(3, "statements/test/" + statementId + ".pdf");
            ps.setString(4, "e".repeat(64));
            ps.execute();
        }
        return statementId;
    }
}
