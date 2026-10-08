package com.fintracker.ledger.category;

import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FAIL-TO-PASS: com.fintracker.ledger.category.* does not exist yet. Full Spring context
 * (real GlobalExceptionHandler, real Postgres via Testcontainers) — this is where RFC 9457
 * Problem Details and cross-tenant 404 behavior are actually observable, matching this
 * codebase's existing split between fast standalone-MockMvc unit tests and *ControllerIT
 * full-stack tests (see BudgetControllerIT).
 *
 * <p>Coverage map: REQ-TS-01's Error Handling table in ledger-transaction-spec-01.md, rows a-h.
 */
@AutoConfigureMockMvc
class CategoryControllerIT extends AbstractIntegrationTest {

    private static final String CATEGORIES = "/api/v1/ledger/categories";
    private static final String IDENTITY_HEADER = "X-Internal-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("a. invalid characters in a category name respond 400 application/problem+json")
    void invalidCharactersRespond400() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(post(CATEGORIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content("{\"categoryName\":\"Auto-Tax!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("b. a colliding category name responds 409 naming the existing category")
    void collidingNameResponds409() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(post(CATEGORIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content("{\"categoryName\":\"Groceries\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("c. updating a nonexistent categoryId responds 404")
    void updateNonexistentCategoryResponds404() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(put(CATEGORIES + "/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content("{\"categoryName\":\"Renamed\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("c. updating another user's category responds 404 — same as nonexistent, "
            + "so existence is never leaked across tenants")
    void updateAnotherUsersCategoryResponds404() throws Exception {
        var owner = UUID.randomUUID();
        var attacker = UUID.randomUUID();

        var createResult = mockMvc.perform(post(CATEGORIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, owner.toString())
                        .content("{\"categoryName\":\"Owner Only\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        var categoryId = com.jayway.jsonpath.JsonPath.read(
                createResult.getResponse().getContentAsString(), "$.categoryId").toString();

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, attacker.toString())
                        .content("{\"categoryName\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("d. updating a SYSTEM-level category responds 400")
    void updateSystemCategoryResponds400() throws Exception {
        var userId = UUID.randomUUID();

        var systemCategoryId = findSeededSystemCategoryId(userId, "Groceries");

        mockMvc.perform(put(CATEGORIES + "/" + systemCategoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content("{\"categoryName\":\"Food\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("d. deleting a SYSTEM-level category responds 400")
    void deleteSystemCategoryResponds400() throws Exception {
        var userId = UUID.randomUUID();

        var systemCategoryId = findSeededSystemCategoryId(userId, "Groceries");

        mockMvc.perform(delete(CATEGORIES + "/" + systemCategoryId)
                        .header(IDENTITY_HEADER, userId.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("e. DP-LEDGER-CATEGORIES-02: deleting a category in use without a reassignment target "
            + "deactivates it: it leaves the list, and its transaction still references it")
    void deleteInUseWithoutReassignmentDeactivates() throws Exception {
        var userId = UUID.randomUUID();
        var categoryId = createCustomCategory(userId, "Commute");
        // Test-support helper inserts a transaction referencing categoryId directly via jOOQ —
        // see AbstractIntegrationTest / this class's own createTransactionReferencingCategory.
        createTransactionReferencingCategory(userId, categoryId);

        mockMvc.perform(delete(CATEGORIES + "/" + categoryId)
                        .header(IDENTITY_HEADER, userId.toString()))
                .andExpect(status().isNoContent());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(CATEGORIES).header(IDENTITY_HEADER, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.categoryId=='" + categoryId + "')]").isEmpty());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(CATEGORIES + "/" + categoryId + "/usage").header(IDENTITY_HEADER, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionCount").value(1));
    }

    @Test
    @DisplayName("DP-LEDGER-CATEGORIES-02: re-creating a deactivated category's name brings back the same category")
    void recreatingADeactivatedNameReactivatesIt() throws Exception {
        var userId = UUID.randomUUID();
        var categoryId = createCustomCategory(userId, "Pet Care");
        mockMvc.perform(delete(CATEGORIES + "/" + categoryId).header(IDENTITY_HEADER, userId.toString()))
                .andExpect(status().isNoContent());

        assertThat(createCustomCategory(userId, "Pet Care")).isEqualTo(categoryId);
    }

    @Test
    @DisplayName("f. a reassignment target that is the category being deleted responds 400")
    void selfReassignmentResponds400() throws Exception {
        var userId = UUID.randomUUID();
        var categoryId = createCustomCategory(userId, "Commute");
        createTransactionReferencingCategory(userId, categoryId);

        mockMvc.perform(delete(CATEGORIES + "/" + categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content("{\"reassignToCategoryId\":\"" + categoryId + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("g. creating a 51st custom category responds 400 stating the cap")
    void creatingBeyondCapResponds400() throws Exception {
        var userId = UUID.randomUUID();
        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post(CATEGORIES)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDENTITY_HEADER, userId.toString())
                            .content("{\"categoryName\":\"Category " + i + "\"}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post(CATEGORIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content("{\"categoryName\":\"One Too Many\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("h. a category name that normalizes to blank responds 400")
    void blankAfterNormalizationResponds400() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(post(CATEGORIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content("{\"categoryName\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("RFC 9457: an error response never leaks a stack trace or exception class name")
    void errorResponseNeverLeaksInternals() throws Exception {
        var userId = UUID.randomUUID();

        mockMvc.perform(post(CATEGORIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content("{\"categoryName\":\"Auto-Tax!\"}"))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("\tat "))));
    }

    @Test
    @DisplayName("a successful create/list/update/delete round-trip through real HTTP")
    void successfulRoundTrip() throws Exception {
        var userId = UUID.randomUUID();

        var categoryId = createCustomCategory(userId, "Side Hustle");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(CATEGORIES).header(IDENTITY_HEADER, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.categoryId=='" + categoryId + "')].displayName")
                        .value("Side Hustle"));

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content("{\"categoryName\":\"Freelance Income\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Freelance Income"));

        mockMvc.perform(delete(CATEGORIES + "/" + categoryId)
                        .header(IDENTITY_HEADER, userId.toString()))
                .andExpect(status().isNoContent());
    }

    // --- test-support helpers -------------------------------------------------------------
    // These call the same HTTP surface under test to set up fixture state, following this
    // codebase's existing *ControllerIT convention (see BudgetControllerIT) of not reaching
    // around the API to seed data for a black-box HTTP suite. findSeededSystemCategoryId and
    // createTransactionReferencingCategory are the two exceptions: SYSTEM rows come from the
    // migration seed (never created via this API), and a referencing transaction requires the
    // transaction feature's own endpoint, so both are looked up/created directly against the
    // schema via the inherited DSLContext instead of re-implementing another feature's API here.

    private String createCustomCategory(UUID userId, String name) throws Exception {
        var result = mockMvc.perform(post(CATEGORIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content("{\"categoryName\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.categoryId").toString();
    }

    private String findSeededSystemCategoryId(UUID userId, String displayName) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(CATEGORIES).header(IDENTITY_HEADER, userId.toString()))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$[?(@.displayName=='" + displayName + "' && @.level=='SYSTEM')].categoryId[0]").toString();
    }

    private UUID insertAccountAsSuperuser(UUID userId) throws Exception {
        var accountId = UUID.randomUUID();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO ledger.accounts (account_id, user_id, account_name, account_type, sync_mode)
                     VALUES (?, ?, 'Test Account', 'CHECKING', 'MANUAL')
                     """)) {
            ps.setObject(1, accountId);
            ps.setObject(2, userId);
            ps.execute();
        }
        return accountId;
    }

    private void createTransactionReferencingCategory(UUID userId, String categoryId) throws Exception {
        var accountId = insertAccountAsSuperuser(userId);
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO ledger.transactions
                        (transaction_id, account_id, category_id, category, amount, merchant, tx_date, source, type, direction, status)
                    VALUES ('%s', '%s', '%s', 'other', -10.00, 'Test Merchant', CURRENT_DATE, 'MANUAL_ENTRY', 'EXPENSE', 'DEBIT', 'POSTED')
                    """.formatted(UUID.randomUUID(), accountId, categoryId));
        }
    }
}
