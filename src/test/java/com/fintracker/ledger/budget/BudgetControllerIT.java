package com.fintracker.ledger.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-5.1 D. REST API Mapping — status codes, and RFC 9457 Problem Details on every error path.
 *
 * <p><b>Path assumption.</b> REQ-5.1 writes the endpoint as {@code /api/v1/budgets}, but every
 * controller in this service is mounted under {@code /api/v1/ledger/*} (transactions, accounts,
 * statements, budgets today). These tests use {@code /api/v1/ledger/budgets}; if the requirement's
 * literal path is intended instead, it is a one-constant change here and a matching change to the
 * controller's {@code @RequestMapping}.
 *
 * <p>The 201-versus-200 split is the reason this suite exists at HTTP level at all: both outcomes
 * come from the same verb on the same URL, so the distinction cannot be observed anywhere below
 * the controller.
 */
@AutoConfigureMockMvc
class BudgetControllerIT extends AbstractBudgetIT {

    private static final String BUDGETS = "/api/v1/ledger/budgets";
    private static final String IDENTITY_HEADER = "X-Internal-User-Id";

    @Autowired
    private MockMvc mockMvc;

    private String payload(String month, String category, String limitAmount) {
        return """
                {"effectiveMonth": "%s", "templateId": null,
                 "lines": [{"category": "%s", "limitAmount": %s}]}
                """.formatted(month, category, limitAmount);
    }

    // REQ-5.1 Success Responses: "201 CREATED — Returned when a brand new monthly budget is
    // successfully created."
    @Test
    @DisplayName("PUT creating a brand new budget responds 201 Created")
    void creatingANewBudgetResponds201() throws Exception {
        mockMvc.perform(put(BUDGETS)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(currentMonth().toString(), "Groceries", "500.00")))
                .andExpect(status().isCreated());
    }

    // REQ-5.1 Success Responses: "200 OK — Returned when an existing monthly budget is updated".
    // Same verb, same URL — only the prior existence of the budget distinguishes the two.
    @Test
    @DisplayName("PUT updating an existing budget responds 200 OK")
    void updatingAnExistingBudgetResponds200() throws Exception {
        budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));

        mockMvc.perform(put(BUDGETS)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(currentMonth().toString(), "Groceries", "600.00")))
                .andExpect(status().isOk());
    }

    // REQ-5.1 D. REST API Mapping: "POST /api/v1/budgets/{id}/close" and Success Responses:
    // "200 OK — ... reopened or closed".
    @Test
    @DisplayName("POST /{id}/close responds 200 OK and reports the budget as CLOSED")
    void closingRespondsOkAndReportsClosed() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));

        mockMvc.perform(post(BUDGETS + "/" + budget.budgetId() + "/close")
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    // REQ-5.1 D. REST API Mapping: "POST /api/v1/budgets/{id}/reopen".
    @Test
    @DisplayName("POST /{id}/reopen responds 200 OK and reports the budget as ACTIVE")
    void reopeningRespondsOkAndReportsActive() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        budgetService.closeBudget(userId, budget.budgetId());

        mockMvc.perform(post(BUDGETS + "/" + budget.budgetId() + "/reopen")
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // REQ-5.1 Error Mappings: "422 UNPROCESSABLE ENTITY — Thrown when HistoricalBudgetException
    // occurs (attempted write operation on a CLOSED budget)." Not 400: the payload is valid, the
    // target's state is what forbids the write.
    @Test
    @DisplayName("writing to a CLOSED budget responds 422 Unprocessable Entity")
    void writingToAClosedBudgetResponds422() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        budgetService.closeBudget(userId, budget.budgetId());

        mockMvc.perform(put(BUDGETS)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(currentMonth().toString(), "Groceries", "600.00")))
                .andExpect(status().isUnprocessableEntity());
    }

    // REQ-5.1 Error Mappings: "400 BAD REQUEST — Thrown when InvalidBudgetException ... occurs."
    @Test
    @DisplayName("an out-of-range limit responds 400 Bad Request")
    void anInvalidLimitResponds400() throws Exception {
        mockMvc.perform(put(BUDGETS)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(currentMonth().toString(), "Groceries", "1000000000.00")))
                .andExpect(status().isBadRequest());
    }

    // REQ-5.1 Error Mappings + E. Interface Details: an unknown budget id is 404, and — per the
    // tenancy rule — so is another user's.
    @Test
    @DisplayName("closing an unknown budget responds 404 Not Found")
    void closingAnUnknownBudgetResponds404() throws Exception {
        mockMvc.perform(post(BUDGETS + "/" + UUID.randomUUID() + "/close")
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isNotFound());
    }

    // Ledger-wide convention (see GlobalExceptionHandler) applied to REQ-5.1's error mappings:
    // errors are RFC 9457 Problem Details, served as application/problem+json.
    @Test
    @DisplayName("error responses are served as application/problem+json")
    void errorsUseProblemJsonContentType() throws Exception {
        mockMvc.perform(put(BUDGETS)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(currentMonth().toString(), "Groceries", "-1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // RFC 9457: the document's own "status" member must agree with the HTTP status line, and the
    // response must carry a type and a title rather than a bare status code.
    @Test
    @DisplayName("the problem document carries type, title and a status matching the HTTP status")
    void problemDocumentIsWellFormed() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        budgetService.closeBudget(userId, budget.budgetId());

        mockMvc.perform(put(BUDGETS)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(currentMonth().toString(), "Groceries", "600.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists());
    }

    // GlobalExceptionHandler's stated contract: "Never exposes internal stack traces or sensitive
    // data to clients." A new exception type is exactly where that guarantee gets lost, because an
    // unmapped exception falls through to the container's default error page.
    @Test
    @DisplayName("an error response never leaks a stack trace")
    void errorResponsesDoNotLeakStackTraces() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        budgetService.closeBudget(userId, budget.budgetId());

        mockMvc.perform(put(BUDGETS)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(currentMonth().toString(), "Groceries", "600.00")))
                .andExpect(content().string(not(containsString("com.fintracker.ledger.budget.service.impl"))))
                .andExpect(content().string(not(containsString("java.lang.StackTraceElement"))));
    }

    // REQ-5.1 C. Data Impacts — status is a two-valued state, and the API surfaces it by name.
    // An ordinal (0/1) would be a silent breaking change for every consumer the moment a third
    // state is ever added.
    @Test
    @DisplayName("status is serialized as its name, not as an ordinal")
    void statusIsSerializedAsAName() throws Exception {
        mockMvc.perform(put(BUDGETS)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(currentMonth().toString(), "Groceries", "500.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // REQ-5.1 Normalization Rule, observed at the API boundary: the response states the month the
    // budget was actually filed under, so a client sending the 17th is told it holds the 1st.
    @Test
    @DisplayName("the response reports the normalized effective month")
    void responseReportsTheNormalizedMonth() throws Exception {
        mockMvc.perform(put(BUDGETS)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("2026-03-17", "Groceries", "500.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveMonth").value("2026-03-01"));
    }
}
