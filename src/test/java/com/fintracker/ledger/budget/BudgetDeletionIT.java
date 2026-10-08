package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.exception.HistoricalBudgetException;
import com.fintracker.ledger.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-5.1 A.3 "Delete Budget" — removing a whole monthly budget.
 *
 * <p>Deletion is a write, so the same guard that governs every other write governs it: permission
 * keys off <b>status</b>, not off the period having elapsed. An ACTIVE budget for a past month is
 * deletable; a CLOSED budget for the current month is not. That pairing is the most likely thing
 * for an implementation to get wrong, so both directions are pinned below.
 *
 * <p>Deletion assertions read the database directly rather than re-reading through the service:
 * {@code getBudgetForMonth} lazily re-creates a budget for a month that has none, so a
 * service-level read would report a brand-new row and make a no-op delete look successful.
 */
@AutoConfigureMockMvc
class BudgetDeletionIT extends AbstractBudgetIT {

    private static final String BUDGETS = "/api/v1/ledger/budgets";
    private static final String IDENTITY_HEADER = "X-Internal-User-Id";

    @Autowired
    private MockMvc mockMvc;

    /** True when the budget row is gone from ledger.budgets entirely. */
    private boolean budgetRowExists(UUID budgetId) {
        return !queryAsSuperuser(
                "SELECT budget_id FROM ledger.budgets WHERE budget_id = ?", budgetId).isEmpty();
    }

    // ── A.3 "delete a monthly 'ACTIVE' budget only" ──────────────────────────────

    // A.3: "The user can delete a monthly 'ACTIVE' budget only."
    @Test
    @DisplayName("an ACTIVE budget is deleted")
    void activeBudgetIsDeleted() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));

        budgetService.deleteBudget(userId, budget.budgetId());

        assertThat(budgetRowExists(budget.budgetId())).isFalse();
    }

    // A.3 Deletion Guard: "Deleting a budget whose status == 'CLOSED' shall be rejected with a
    // HistoricalBudgetException (422)."
    @Test
    @DisplayName("deleting a CLOSED budget is rejected with HistoricalBudgetException")
    void deletingAClosedBudgetIsRejected() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        budgetService.closeBudget(userId, budget.budgetId());

        assertThatThrownBy(() -> budgetService.deleteBudget(userId, budget.budgetId()))
                .isInstanceOf(HistoricalBudgetException.class);
    }

    // A.3 Deletion Guard — the rejection must be effective, not merely thrown. A delete that
    // raises after the row is already gone would pass the test above and fail here.
    @Test
    @DisplayName("a rejected deletion leaves the CLOSED budget and its lines intact")
    void rejectedDeletionLeavesTheBudgetIntact() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        budgetService.closeBudget(userId, budget.budgetId());

        assertThatThrownBy(() -> budgetService.deleteBudget(userId, budget.budgetId()))
                .isInstanceOf(HistoricalBudgetException.class);

        assertThat(budgetRowExists(budget.budgetId())).isTrue();
        assertThat(countLineRows(budget.budgetId())).isEqualTo(1);
    }

    // A.3 Deletion Guard + "Reopening Exemption": reopening restores full write permission,
    // deletion included — a CLOSED budget is not permanently undeletable.
    @Test
    @DisplayName("a reopened budget can be deleted")
    void reopenedBudgetCanBeDeleted() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        budgetService.closeBudget(userId, budget.budgetId());
        budgetService.reopenBudget(userId, budget.budgetId());

        budgetService.deleteBudget(userId, budget.budgetId());

        assertThat(budgetRowExists(budget.budgetId())).isFalse();
    }

    // A.3 Deletion Guard read together with A.1 Modification Guard: the guard keys off status,
    // so an ACTIVE budget whose month has elapsed is still deletable. An implementation that
    // guards on "is this month in the past" passes the tests above and fails here.
    @Test
    @DisplayName("an ACTIVE budget for a past period is deletable")
    void activePastPeriodBudgetIsDeletable() {
        var budget = budgetService.upsertBudget(userId, pastMonth(), null, List.of(line("Groceries", "500.00")));

        budgetService.deleteBudget(userId, budget.budgetId());

        assertThat(budgetRowExists(budget.budgetId())).isFalse();
    }

    // A.3 Cascade: "Deleting a budget removes its ledger.budget_lines records along with it.
    // No orphaned line items may remain."
    @Test
    @DisplayName("deleting a budget removes its line items")
    void deletingABudgetCascadesToItsLines() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null,
                List.of(line("Groceries", "500.00"), line("Dining", "200.00"), line("Fuel", "80.00")));

        budgetService.deleteBudget(userId, budget.budgetId());

        assertThat(countLineRows(budget.budgetId()))
                .as("no orphaned budget_lines may survive the parent budget")
                .isZero();
    }

    // A.3 Orphaned Transaction Handling: "Underlying ledger.transactions records are never
    // altered or deleted."
    @Test
    @DisplayName("deleting a budget leaves the user's transactions untouched")
    void deletingABudgetDoesNotTouchTransactions() {
        var accountId = insertAccount(userId);
        var transactionId = insertPostedExpense(accountId, "Groceries", "75.00", currentMonth().plusDays(3));
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));

        budgetService.deleteBudget(userId, budget.budgetId());

        assertThat(queryAsSuperuser(
                "SELECT transaction_id FROM ledger.transactions WHERE transaction_id = ?", transactionId))
                .as("a budget is a ceiling, not a container — deleting it must not delete spending")
                .hasSize(1);
    }

    // A.3 Ownership: "Deleting a budget that ... belongs to another user shall be rejected with a
    // ResourceNotFoundException (404). A user must never be able to distinguish 'does not exist'
    // from 'is not yours'."
    @Test
    @DisplayName("deleting another user's budget is a 404-class failure and leaves it intact")
    void deletingAnotherUsersBudgetIsNotFound() {
        var otherUserId = UUID.randomUUID();
        actAs(otherUserId);
        var theirBudget = budgetService.upsertBudget(otherUserId, currentMonth(), null, List.of(line("A", "1.00")));

        actAs(userId);
        assertThatThrownBy(() -> budgetService.deleteBudget(userId, theirBudget.budgetId()))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(budgetRowExists(theirBudget.budgetId())).isTrue();
    }

    @Test
    @DisplayName("deleting an unknown budget id is a 404-class failure")
    void deletingAnUnknownBudgetIsNotFound() {
        assertThatThrownBy(() -> budgetService.deleteBudget(userId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // A.3 Non-Idempotent: "Deleting an already-deleted budget is a 404, not a silent success —
    // the second caller is acting on a stale view."
    @Test
    @DisplayName("deleting the same budget twice fails the second time")
    void secondDeleteOfTheSameBudgetIsNotFound() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("A", "1.00")));
        budgetService.deleteBudget(userId, budget.budgetId());

        assertThatThrownBy(() -> budgetService.deleteBudget(userId, budget.budgetId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // A.3 + A.1 Period Selection: deleting a month frees it to be budgeted again, rather than
    // leaving the unique (user_id, effective_month) slot permanently consumed.
    @Test
    @DisplayName("a deleted month can be budgeted again from scratch")
    void aDeletedMonthCanBeRecreated() {
        var month = currentMonth();
        var original = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));
        budgetService.deleteBudget(userId, original.budgetId());

        var recreated = budgetService.upsertBudget(userId, month, null, List.of(line("Dining", "250.00")));

        assertThat(recreated.budgetId()).isNotEqualTo(original.budgetId());
        assertThat(recreated.lines()).singleElement()
                .satisfies(l -> assertThat(l.category()).isEqualTo("Dining"));
    }

    // ── D. REST API Mapping — DELETE /api/v1/budgets/{id} ────────────────────────

    // D. Success Responses: "204 NO CONTENT — Returned when a budget is successfully deleted.
    // The response carries no body."
    @Test
    @DisplayName("DELETE /{id} responds 204 No Content with an empty body")
    void deleteResponds204WithNoBody() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));

        mockMvc.perform(delete(BUDGETS + "/" + budget.budgetId())
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(budgetRowExists(budget.budgetId())).isFalse();
    }

    // D. Error Mappings: "422 UNPROCESSABLE ENTITY — ... deletion of, a CLOSED budget."
    @Test
    @DisplayName("DELETE on a CLOSED budget responds 422 Unprocessable Entity")
    void deletingAClosedBudgetResponds422() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        budgetService.closeBudget(userId, budget.budgetId());

        mockMvc.perform(delete(BUDGETS + "/" + budget.budgetId())
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isUnprocessableEntity());
    }

    // D. Error Mappings: "404 NOT FOUND — ... deleting a budget that does not exist or belongs to
    // another user."
    @Test
    @DisplayName("DELETE on an unknown budget responds 404 Not Found")
    void deletingAnUnknownBudgetResponds404() throws Exception {
        mockMvc.perform(delete(BUDGETS + "/" + UUID.randomUUID())
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isNotFound());
    }

    // RFC 9457 Problem Details, applied to A.3's error paths like every other error in the module.
    @Test
    @DisplayName("a rejected DELETE is served as application/problem+json with a matching status")
    void rejectedDeleteIsAProblemDetail() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        budgetService.closeBudget(userId, budget.budgetId());

        mockMvc.perform(delete(BUDGETS + "/" + budget.budgetId())
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").exists());
    }
}
