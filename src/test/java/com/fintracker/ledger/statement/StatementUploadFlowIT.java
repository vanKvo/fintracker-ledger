package com.fintracker.ledger.statement;

import com.fintracker.ledger.statement.service.S3PresignService;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-STMT-03/06/07: the user-facing initiate-upload flow, end to end against real
 * Postgres — the things a Mockito unit test of StatementServiceImpl cannot prove:
 *
 *   1. The happy path is 202 (async acceptance, not completion) and the created row's
 *      statementMonth is the month the DATABASE derived from closing_date (V16 generated
 *      column) — read back through GET /statements, so the derivation is observed through
 *      the API, not asserted from the request.
 *   2. The synchronous duplicate checks return a 409 ProblemDetail whose properties carry
 *      the existing statement's identity (EXACT_FILE / SAME_MONTH), and the checks are
 *      evaluated against the live schema (unique index, generated column) — EXACT_FILE
 *      winning over SAME_MONTH when both match.
 *   3. A client cannot opt out of duplicate detection: contentHash is @NotNull, so
 *      omitting it is a 400, not an unchecked upload.
 *
 * S3PresignService is mocked: this suite tests the Ledger's own behavior, not AWS —
 * the presign call itself is exercised by S3PresignService's unit tests.
 */
@AutoConfigureMockMvc
class StatementUploadFlowIT extends AbstractIntegrationTest {

    private static final String INITIATE_UPLOAD = "/api/v1/ledger/statements/initiate-upload";
    private static final String STATEMENTS = "/api/v1/ledger/statements";
    private static final String IDENTITY_HEADER = "X-Internal-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private S3PresignService s3PresignService;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void fixtures() throws SQLException {
        userId = UUID.randomUUID();
        accountId = insertAccountAsSuperuser(userId);
    }

    @Test
    @DisplayName("initiating an upload responds 202 with the job handle, presigned URL and object key")
    void initiatingAnUploadResponds202() throws Exception {
        stubPresign();

        mockMvc.perform(post(INITIATE_UPLOAD)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadPayload(accountId, "PDF", null,
                                "2026-08-03", "2026-09-02", "f".repeat(64))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.uploadUrl").value("https://s3.test/presigned-put"))
                .andExpect(jsonPath("$.s3ObjectKey").value(startsWith("statements/")));
    }

    @Test
    @DisplayName("the created statement's month is derived from closing_date by the database "
            + "(V16 generated column), observed through GET /statements")
    void statementMonthIsDerivedFromClosingDate() throws Exception {
        stubPresign();

        mockMvc.perform(post(INITIATE_UPLOAD)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadPayload(accountId, "PDF", null,
                                "2026-08-03", "2026-09-02", "f".repeat(64))))
                .andExpect(status().isAccepted());

        mockMvc.perform(get(STATEMENTS).header(IDENTITY_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].statementMonth").value("2026-09-01"))
                .andExpect(jsonPath("$[0].contentHash").value("f".repeat(64)))
                .andExpect(jsonPath("$[0].openingDate").value("2026-08-03"))
                .andExpect(jsonPath("$[0].closingDate").value("2026-09-02"))
                .andExpect(jsonPath("$[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$[0].txCount").value(0));
    }

    @Test
    @DisplayName("re-uploading the exact same file responds 409 with matchType EXACT_FILE "
            + "and the existing statement's identity")
    void sameExactFileIs409ExactFile() throws Exception {
        var existingStatementId = insertStatementAsSuperuser(accountId,
                "2026-08-03", "2026-09-02", "a".repeat(64));

        mockMvc.perform(post(INITIATE_UPLOAD)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadPayload(accountId, "PDF", null,
                                "2026-08-03", "2026-09-02", "a".repeat(64))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("/problems/duplicate-statement")))
                .andExpect(jsonPath("$.matchType").value("EXACT_FILE"))
                .andExpect(jsonPath("$.existingStatementId").value(existingStatementId.toString()))
                .andExpect(jsonPath("$.existingUploadDate").isNotEmpty())
                .andExpect(jsonPath("$.existingTransactionCount").value(0));
    }

    @Test
    @DisplayName("a different file covering an already-uploaded month responds 409 SAME_MONTH")
    void differentFileSameMonthIs409SameMonth() throws Exception {
        var existingStatementId = insertStatementAsSuperuser(accountId,
                "2026-08-03", "2026-09-02", "a".repeat(64));

        mockMvc.perform(post(INITIATE_UPLOAD)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadPayload(accountId, "PDF", null,
                                "2026-08-25", "2026-09-20", "b".repeat(64))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("/problems/duplicate-statement")))
                .andExpect(jsonPath("$.matchType").value("SAME_MONTH"))
                .andExpect(jsonPath("$.existingStatementId").value(existingStatementId.toString()));
    }

    @Test
    @DisplayName("the duplicate check is scoped to the account: the same file on a DIFFERENT "
            + "account of the same user is not a duplicate")
    void sameFileOnAnotherAccountIsNotADuplicate() throws Exception {
        var otherAccountId = insertAccountAsSuperuser(userId);
        insertStatementAsSuperuser(otherAccountId, "2026-08-03", "2026-09-02", "a".repeat(64));
        stubPresign();

        mockMvc.perform(post(INITIATE_UPLOAD)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadPayload(accountId, "PDF", null,
                                "2026-08-03", "2026-09-02", "a".repeat(64))))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("an upload without a contentHash is rejected 400 — no client can opt out "
            + "of duplicate detection by omitting the field")
    void missingContentHashIs400() throws Exception {
        mockMvc.perform(post(INITIATE_UPLOAD)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": "%s", "sourceFormat": "PDF", "fileName": "stmt.pdf",
                                 "openingDate": "2026-08-03", "closingDate": "2026-09-02"}
                                """.formatted(accountId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a closingDate before openingDate is rejected 400")
    void closingBeforeOpeningIs400() throws Exception {
        mockMvc.perform(post(INITIATE_UPLOAD)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadPayload(accountId, "PDF", null,
                                "2026-09-02", "2026-08-03", "c".repeat(64))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V17/V16: the unique indexes reject a second statement with the same "
            + "content hash or the same closing month — the DB-level backstop that turns "
            + "a lost check-then-insert race into a constraint violation the service can translate")
    void uniqueIndexesBackstopBothDuplicateKinds() throws Exception {
        insertStatementAsSuperuser(accountId, "2026-08-03", "2026-09-02", "a".repeat(64));

        // Same content hash, different month: idx_unique_account_content_hash (V17) rejects it.
        assertThatThrownBy(() ->
                insertStatementAsSuperuser(accountId, "2026-09-05", "2026-10-02", "a".repeat(64)))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));

        // Different hash, same closing month: idx_unique_account_statement_month (V16) rejects it.
        assertThatThrownBy(() ->
                insertStatementAsSuperuser(accountId, "2026-08-10", "2026-09-20", "b".repeat(64)))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
    }

    @Test
    @DisplayName("an accountId belonging to another user is rejected 400 — the backend never "
            + "trusts a user-supplied ID for data scoping")
    void foreignAccountIs400() throws Exception {
        var foreignAccountId = insertAccountAsSuperuser(UUID.randomUUID());

        mockMvc.perform(post(INITIATE_UPLOAD)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadPayload(foreignAccountId, "PDF", null,
                                "2026-08-03", "2026-09-02", "d".repeat(64))))
                .andExpect(status().isBadRequest());
    }

    private void stubPresign() {
        when(s3PresignService.presignStatementUpload(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new S3PresignService.PresignedUpload(
                        "https://s3.test/presigned-put",
                        "statements/%s/%s/stmt.pdf".formatted(
                                invocation.getArgument(0), invocation.getArgument(1))));
    }

    private String uploadPayload(UUID accountId, String sourceFormat, String bankId,
                                 String opening, String closing, String contentHash) {
        return """
                {"accountId": "%s", "description": "Test statement", "sourceFormat": "%s",
                 "fileName": "stmt.pdf", "bankId": %s,
                 "openingDate": "%s", "closingDate": "%s",
                 "contentHash": "%s", "overwriteStatementId": null}
                """.formatted(accountId, sourceFormat,
                bankId == null ? "null" : "\"" + bankId + "\"",
                opening, closing, contentHash);
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

    private UUID insertStatementAsSuperuser(UUID accountId, String openingDate, String closingDate,
                                            String contentHash) throws SQLException {
        var statementId = UUID.randomUUID();
        try (Connection conn = superuserConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ledger.statements
                         (statement_id, account_id, s3_object_key, status, opening_date, closing_date, content_hash)
                     VALUES (?, ?, ?, 'COMPLETED', ?, ?, ?)
                     """)) {
            ps.setObject(1, statementId);
            ps.setObject(2, accountId);
            ps.setObject(3, "statements/test/" + statementId + ".pdf");
            ps.setObject(4, java.time.LocalDate.parse(openingDate));
            ps.setObject(5, java.time.LocalDate.parse(closingDate));
            ps.setString(6, contentHash);
            ps.execute();
        }
        return statementId;
    }
}
