package com.fintracker.ledger.transaction.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TXT-01 through the public transactions API: a missing type is rejected, the type/direction rule
 * (EXPENSE is money out; INCOME and REFUND are money in), and the new transaction-table fields on
 * create, list and update.
 */
@AutoConfigureMockMvc
class TransactionControllerIT extends AbstractIntegrationTest {

    private static final String TRANSACTIONS = "/api/v1/ledger/transactions";
    private static final String IDENTITY_HEADER = "X-Internal-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void ownAccount() throws SQLException {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        try (var conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var ps = conn.prepareStatement("""
                     INSERT INTO ledger.accounts (account_id, user_id, account_name, account_type, sync_mode)
                     VALUES (?, ?, 'Test Account', 'CHECKING', 'MANUAL')
                     """)) {
            ps.setObject(1, accountId);
            ps.setObject(2, userId);
            ps.execute();
        }
    }

    private String manualBody(String typeJson, String direction, String extra) {
        return """
                {"accountId":"%s","amount":%s,"merchant":"Store","category":"Shopping",
                 %s"direction":"%s"%s}
                """.formatted(accountId, direction.equals("DEBIT") ? "-25.00" : "25.00",
                typeJson == null ? "" : "\"type\":\"" + typeJson + "\",", direction, extra);
    }

    private JsonNode create(String body) throws Exception {
        var response = mockMvc.perform(post(TRANSACTIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode list(String query) throws Exception {
        var response = mockMvc.perform(get(TRANSACTIONS + query).header(IDENTITY_HEADER, userId.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode find(String id) throws Exception {
        for (var tx : list("")) {
            if (tx.get("transactionId").asText().equals(id)) {
                return tx;
            }
        }
        throw new AssertionError("transaction not listed: " + id);
    }

    // ------------------------------------------------------------------- create

    @ParameterizedTest
    @CsvSource({"CREDIT", "DEBIT"})
    @DisplayName("POST without a type responds 400 and stores nothing")
    void createWithoutTypeResponds400(String direction) throws Exception {
        mockMvc.perform(post(TRANSACTIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content(manualBody(null, direction, "")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(list("")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"EXPENSE,CREDIT", "INCOME,DEBIT", "REFUND,DEBIT"})
    @DisplayName("POST with EXPENSE as money in, or INCOME/REFUND as money out, responds 400")
    void createWithMismatchedDirectionResponds400(String type, String direction) throws Exception {
        mockMvc.perform(post(TRANSACTIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content(manualBody(type, direction, "")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("POST returns every new field: direction, currency, isRecurring, linkedTransactionId")
    void createReturnsNewFields() throws Exception {
        var expense = create(manualBody("EXPENSE", "DEBIT", ""));

        var refund = create(manualBody("REFUND", "CREDIT",
                ",\"currency\":\"CAD\",\"isRecurring\":true,\"linkedTransactionId\":\""
                        + expense.get("transactionId").asText() + "\""));

        assertThat(refund.get("direction").asText()).isEqualTo("CREDIT");
        assertThat(refund.get("currency").asText()).isEqualTo("CAD");
        assertThat(refund.get("isRecurring").asBoolean()).isTrue();
        assertThat(refund.get("linkedTransactionId").asText()).isEqualTo(expense.get("transactionId").asText());
    }

    @Test
    @DisplayName("POST linking to a transaction the user doesn't own responds 400")
    void createLinkedToUnknownTransactionResponds400() throws Exception {
        mockMvc.perform(post(TRANSACTIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content(manualBody("REFUND", "CREDIT",
                                ",\"linkedTransactionId\":\"" + UUID.randomUUID() + "\"")))
                .andExpect(status().isBadRequest());
    }

    // --------------------------------------------------------------------- list

    @Test
    @DisplayName("GET filters by type and by direction")
    void listFiltersByTypeAndDirection() throws Exception {
        create(manualBody("EXPENSE", "DEBIT", ""));
        create(manualBody("REFUND", "CREDIT", ""));
        create(manualBody("TRANSFER", "CREDIT", ""));

        var refunds = list("?type=REFUND");
        var credits = list("?direction=CREDIT");

        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).get("type").asText()).isEqualTo("REFUND");
        assertThat(credits).hasSize(2);
        credits.forEach(tx -> assertThat(tx.get("direction").asText()).isEqualTo("CREDIT"));
    }

    // ------------------------------------------------------------------- update

    private void patchTransaction(String id, String body, int expectedStatus) throws Exception {
        mockMvc.perform(patch(TRANSACTIONS + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDENTITY_HEADER, userId.toString())
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    @DisplayName("PATCH changes type and direction together")
    void patchChangesTypeAndDirection() throws Exception {
        var id = create(manualBody("EXPENSE", "DEBIT", "")).get("transactionId").asText();

        patchTransaction(id, "{\"type\":\"REFUND\",\"direction\":\"CREDIT\"}", 204);

        var updated = find(id);
        assertThat(updated.get("type").asText()).isEqualTo("REFUND");
        assertThat(updated.get("direction").asText()).isEqualTo("CREDIT");
    }

    @Test
    @DisplayName("PATCH with a type that conflicts with the stored direction responds 400")
    void patchConflictingTypeResponds400() throws Exception {
        var id = create(manualBody("EXPENSE", "DEBIT", "")).get("transactionId").asText();

        patchTransaction(id, "{\"type\":\"INCOME\"}", 400);

        assertThat(find(id).get("type").asText()).isEqualTo("EXPENSE");
    }

    @Test
    @DisplayName("PATCH sets isRecurring and linkedTransactionId")
    void patchSetsRecurringAndLink() throws Exception {
        var expenseId = create(manualBody("EXPENSE", "DEBIT", "")).get("transactionId").asText();
        var refundId = create(manualBody("REFUND", "CREDIT", "")).get("transactionId").asText();

        patchTransaction(refundId, "{\"isRecurring\":true,\"linkedTransactionId\":\"" + expenseId + "\"}", 204);

        var updated = find(refundId);
        assertThat(updated.get("isRecurring").asBoolean()).isTrue();
        assertThat(updated.get("linkedTransactionId").asText()).isEqualTo(expenseId);
    }

    @Test
    @DisplayName("PATCH linking a transaction to itself responds 400")
    void patchSelfLinkResponds400() throws Exception {
        var id = create(manualBody("REFUND", "CREDIT", "")).get("transactionId").asText();

        patchTransaction(id, "{\"linkedTransactionId\":\"" + id + "\"}", 400);
    }

    @Test
    @DisplayName("PATCH with no field to change responds 400")
    void patchWithNothingResponds400() throws Exception {
        var id = create(manualBody("EXPENSE", "DEBIT", "")).get("transactionId").asText();

        patchTransaction(id, "{}", 400);
    }

    @Test
    @DisplayName("a JSON response exposes the new fields by name")
    void responseExposesNewFieldNames() throws Exception {
        create(manualBody("EXPENSE", "DEBIT", ""));

        mockMvc.perform(get(TRANSACTIONS).header(IDENTITY_HEADER, userId.toString()))
                .andExpect(jsonPath("$[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].isRecurring").value(nullValue()))
                .andExpect(jsonPath("$[0].linkedTransactionId").value(nullValue()));
    }
}
