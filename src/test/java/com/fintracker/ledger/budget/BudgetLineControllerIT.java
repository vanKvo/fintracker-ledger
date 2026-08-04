package com.fintracker.ledger.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-5.2 D. REST API Mapping — status codes and RFC 9457 Problem Details for the
 * {@code BudgetLineController} endpoints.
 */
@AutoConfigureMockMvc
class BudgetLineControllerIT extends AbstractBudgetIT {

    private static final String IDENTITY_HEADER = "X-Internal-User-Id";

    @Autowired
    private MockMvc mockMvc;

    private String linesUrl(UUID budgetId) {
        return "/api/v1/ledger/budgets/" + budgetId + "/lines";
    }

    // REQ-5.2 Success Responses: "201 CREATED — Line item added successfully."
    @Test
    @DisplayName("POST /lines adding a new line item responds 201 Created")
    void addingALineItemResponds201() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        mockMvc.perform(post(linesUrl(budget.budgetId()))
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "Groceries", "limitAmount": 500.00}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("Groceries"))
                .andExpect(jsonPath("$.limitAmount").value(500.00));
    }

    // REQ-5.2 Error Mapping: "409 CONFLICT — Thrown when DuplicateCategoryException occurs."
    @Test
    @DisplayName("POST /lines with a duplicate category responds 409 Conflict")
    void addingADuplicateCategoryResponds409() throws Exception {
        var budget = budgetService.upsertBudget(
                userId, currentMonth(), null, List.of(line("Groceries", "500.00")));

        mockMvc.perform(post(linesUrl(budget.budgetId()))
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "groceries", "limitAmount": 100.00}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // REQ-5.2 Error Mapping: "400 BAD REQUEST — Thrown when InvalidBudgetException ... occurs."
    @Test
    @DisplayName("POST /lines with an out-of-range limitAmount responds 400 Bad Request")
    void addingAnOutOfRangeLimitResponds400() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        mockMvc.perform(post(linesUrl(budget.budgetId()))
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "Groceries", "limitAmount": -5.00}
                                """))
                .andExpect(status().isBadRequest());
    }

    // REQ-5.2 Error Mapping: "422 UNPROCESSABLE ENTITY — budget status is CLOSED."
    @Test
    @DisplayName("POST /lines on a CLOSED budget responds 422 Unprocessable Entity")
    void addingALineToAClosedBudgetResponds422() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());
        budgetService.closeBudget(userId, budget.budgetId());

        mockMvc.perform(post(linesUrl(budget.budgetId()))
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "Groceries", "limitAmount": 500.00}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    // REQ-5.2 Error Mapping: "404 NOT FOUND — budgetId ... not found."
    @Test
    @DisplayName("POST /lines against an unknown budget responds 404 Not Found")
    void addingALineToAnUnknownBudgetResponds404() throws Exception {
        mockMvc.perform(post(linesUrl(UUID.randomUUID()))
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "Groceries", "limitAmount": 500.00}
                                """))
                .andExpect(status().isNotFound());
    }

    // REQ-5.2 Success Responses: "200 OK — Line item updated ... successfully."
    @Test
    @DisplayName("PUT /lines/{lineId} updating a limit responds 200 OK")
    void updatingALineItemLimitResponds200() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();

        mockMvc.perform(put(linesUrl(budget.budgetId()) + "/" + lineId)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limitAmount": 650.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limitAmount").value(650.00));
    }

    // REQ-5.2 Error Mapping: "404 NOT FOUND — lineId not found."
    @Test
    @DisplayName("PUT /lines/{lineId} against an unknown line responds 404 Not Found")
    void updatingAnUnknownLineResponds404() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        mockMvc.perform(put(linesUrl(budget.budgetId()) + "/" + UUID.randomUUID())
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limitAmount": 650.00}
                                """))
                .andExpect(status().isNotFound());
    }

    // REQ-5.2 Success Responses: "200 OK — Line item ... deleted successfully."
    @Test
    @DisplayName("DELETE /lines/{lineId} removing a line item responds 200 OK")
    void removingALineItemResponds200() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();

        mockMvc.perform(delete(linesUrl(budget.budgetId()) + "/" + lineId)
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isOk());
    }

    // REQ-5.2 Error Mapping: "422 UNPROCESSABLE ENTITY — budget status is CLOSED" applies to
    // removal too.
    @Test
    @DisplayName("DELETE /lines/{lineId} on a CLOSED budget responds 422 Unprocessable Entity")
    void removingALineFromAClosedBudgetResponds422() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();
        budgetService.closeBudget(userId, budget.budgetId());

        mockMvc.perform(delete(linesUrl(budget.budgetId()) + "/" + lineId)
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isUnprocessableEntity());
    }

    // REQ-5.2 Error Mapping: "404 NOT FOUND — lineId not found" applies to removal too.
    @Test
    @DisplayName("DELETE /lines/{lineId} against an unknown line responds 404 Not Found")
    void removingAnUnknownLineResponds404() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        mockMvc.perform(delete(linesUrl(budget.budgetId()) + "/" + UUID.randomUUID())
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isNotFound());
    }

    // REQ-5.2 Error Mapping: "422 UNPROCESSABLE ENTITY — budget status is CLOSED" applies to the
    // update-limit path too, not only add/remove.
    @Test
    @DisplayName("PUT /lines/{lineId} on a CLOSED budget responds 422 Unprocessable Entity")
    void updatingALineOnAClosedBudgetResponds422() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();
        budgetService.closeBudget(userId, budget.budgetId());

        mockMvc.perform(put(linesUrl(budget.budgetId()) + "/" + lineId)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limitAmount": 650.00}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // REQ-5.2 Error Mapping: "400 BAD REQUEST — InvalidBudgetException" applies to the
    // update-limit path too.
    @Test
    @DisplayName("PUT /lines/{lineId} with an out-of-range limitAmount responds 400 Bad Request")
    void updatingToAnOutOfRangeLimitResponds400() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();

        mockMvc.perform(put(linesUrl(budget.budgetId()) + "/" + lineId)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limitAmount": 1000000000.00}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // REQ-5.2 Constraints: "Non-Empty Category Name" maps to the same 400 InvalidBudgetException
    // path as the monetary constraints.
    @Test
    @DisplayName("POST /lines with a blank category responds 400 Bad Request")
    void addingALineWithBlankCategoryResponds400() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        mockMvc.perform(post(linesUrl(budget.budgetId()))
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "   ", "limitAmount": 100.00}
                                """))
                .andExpect(status().isBadRequest());
    }
}
