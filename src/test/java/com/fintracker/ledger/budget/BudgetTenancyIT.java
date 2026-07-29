package com.fintracker.ledger.budget;

import com.fintracker.ledger.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-5.1 E. Interface Details — "@throws ResourceNotFoundException If the budgetId does not exist
 * <b>or belong to the user</b>."
 *
 * <p>The two cases are deliberately collapsed into one response. Answering 403 for "exists but
 * isn't yours" would confirm the existence of another tenant's budget to anyone willing to guess
 * UUIDs, so the requirement's phrasing is a security property, not a convenience.
 */
class BudgetTenancyIT extends AbstractBudgetIT {

    /** Creates a budget owned by a different tenant, then restores the ambient tenant. */
    private UUID budgetOwnedBySomeoneElse(UUID otherUserId) {
        actAs(otherUserId);
        var budget = budgetService.upsertBudget(otherUserId, currentMonth(), null,
                List.of(line("Groceries", "500.00")));
        actAs(userId);
        return budget.budgetId();
    }

    // REQ-5.1 reopenBudget: a budget that exists but belongs to another user is reported exactly
    // as a missing one.
    @Test
    @DisplayName("reopening another user's budget fails as not-found, not as forbidden")
    void reopeningAnotherUsersBudgetIsNotFound() {
        var foreignBudgetId = budgetOwnedBySomeoneElse(UUID.randomUUID());

        assertThatThrownBy(() -> budgetService.reopenBudget(userId, foreignBudgetId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // REQ-5.1 closeBudget: same scoping rule on the other lifecycle transition.
    @Test
    @DisplayName("closing another user's budget fails as not-found")
    void closingAnotherUsersBudgetIsNotFound() {
        var foreignBudgetId = budgetOwnedBySomeoneElse(UUID.randomUUID());

        assertThatThrownBy(() -> budgetService.closeBudget(userId, foreignBudgetId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // REQ-5.1 E. Interface Details — the rejection must also be effective: the foreign budget is
    // still ACTIVE afterwards, i.e. the call failed before touching it rather than after.
    @Test
    @DisplayName("a rejected cross-tenant close leaves the other user's budget untouched")
    void rejectedCrossTenantCloseChangesNothing() {
        var otherUserId = UUID.randomUUID();
        var foreignBudgetId = budgetOwnedBySomeoneElse(otherUserId);

        assertThatThrownBy(() -> budgetService.closeBudget(userId, foreignBudgetId))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(readStatusColumn(foreignBudgetId)).isEqualTo("ACTIVE");
    }

    // REQ-5.1 Uniqueness is per user: "The user cannot create duplicate budgets for a month/year
    // that already exists in their account." Two tenants budgeting the same month are unrelated.
    @Test
    @DisplayName("two users hold independent budgets for the same month")
    void twoUsersHoldIndependentBudgetsForTheSameMonth() {
        var otherUserId = UUID.randomUUID();
        var month = currentMonth();

        actAs(otherUserId);
        var theirs = budgetService.upsertBudget(otherUserId, month, null, List.of(line("Groceries", "111.00")));

        actAs(userId);
        var mine = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "222.00")));

        assertThat(mine.budgetId()).isNotEqualTo(theirs.budgetId());
        assertThat(readLineRows(theirs.budgetId())).singleElement()
                .satisfies(row -> assertThat(row.get("limit_amount")).hasToString("111.00"));
        assertThat(readLineRows(mine.budgetId())).singleElement()
                .satisfies(row -> assertThat(row.get("limit_amount")).hasToString("222.00"));
    }

    // REQ-5.1 Automated Period Closure: "all active budgets where month < current_month" — system
    // wide. The scheduler has no user context, so it must not be written as a per-tenant query.
    @Test
    @DisplayName("closePastBudgets closes elapsed budgets belonging to every user")
    void closePastBudgetsSpansAllUsers() {
        var otherUserId = UUID.randomUUID();
        var elapsed = currentMonth().minusMonths(1);

        actAs(otherUserId);
        var theirs = budgetService.upsertBudget(otherUserId, elapsed, null, List.of(line("Groceries", "100.00")));

        actAs(userId);
        var mine = budgetService.upsertBudget(userId, elapsed, null, List.of(line("Groceries", "100.00")));

        budgetService.closePastBudgets(currentMonth());

        assertThat(readStatusColumn(mine.budgetId())).isEqualTo("CLOSED");
        assertThat(readStatusColumn(theirs.budgetId())).isEqualTo("CLOSED");
    }
}
