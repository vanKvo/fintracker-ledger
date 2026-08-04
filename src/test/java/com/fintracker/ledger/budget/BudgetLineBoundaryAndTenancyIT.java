package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.dto.BudgetLineInput;
import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.validation.BudgetMonetaryPolicy;
import com.fintracker.ledger.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-5.2 gap-fill: boundary values for the shared monetary policy, cross-tenant isolation on the
 * update/remove paths (only {@code addLineItem} had tenancy coverage), and category-uniqueness
 * scoping ("within the same budget period", not globally per user).
 *
 * <p>Companion to {@link BudgetLineServiceIT} — kept separate so each file states one coherent
 * slice of REQ-5.2 rather than growing one file unboundedly.
 */
class BudgetLineBoundaryAndTenancyIT extends AbstractBudgetIT {

    // --------------------------------------------------------- limitAmount boundaries (add)

    @Test
    @DisplayName("a limitAmount of exactly 0.00 (inclusive lower bound) is accepted")
    void minimumBoundaryLimitIsAccepted() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        BudgetLine added = budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("Misc", BudgetMonetaryPolicy.MIN_LIMIT_AMOUNT));

        assertThat(added.limitAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a limitAmount of exactly 999,999,999.99 (inclusive upper bound) is accepted")
    void maximumBoundaryLimitIsAccepted() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        BudgetLine added = budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("Mortgage", BudgetMonetaryPolicy.MAX_LIMIT_AMOUNT));

        assertThat(added.limitAmount()).isEqualByComparingTo("999999999.99");
    }

    @Test
    @DisplayName("a limitAmount one cent above the upper bound is rejected")
    void justAboveMaximumBoundaryIsRejected() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        assertThatThrownBy(() -> budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("Mortgage", new BigDecimal("1000000000.00"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    @Test
    @DisplayName("a blank category name is rejected, not silently dropped")
    void blankCategoryIsRejected() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        assertThatThrownBy(() -> budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("   ", new BigDecimal("10.00"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    @Test
    @DisplayName("adding the 50th line item onto a budget already holding 49 succeeds")
    void addingUpToExactlyFiftyLinesSucceeds() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, distinctLines(49));

        BudgetLine added = budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("Fiftieth", new BigDecimal("10.00")));

        assertThat(added.lineId()).isNotNull();
        assertThat(countLineRows(budget.budgetId())).isEqualTo(50);
    }

    // --------------------------------------------------------- limitAmount validation (update)

    @Test
    @DisplayName("updating a line item to an out-of-range limitAmount is rejected and leaves the original value intact")
    void updatingToOutOfRangeLimitIsRejected() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();

        assertThatThrownBy(() -> budgetLineService.updateLineItemLimit(
                userId, budget.budgetId(), lineId, new BigDecimal("1000000000.00")))
                .isInstanceOf(InvalidBudgetException.class);

        var persistedLimit = (BigDecimal) readLineRows(budget.budgetId()).get(0).get("limit_amount");
        assertThat(persistedLimit).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("updating a line item to a limitAmount with more than 2 decimal places is rejected, not rounded")
    void updatingToOverPreciseLimitIsRejectedNotRounded() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();

        assertThatThrownBy(() -> budgetLineService.updateLineItemLimit(
                userId, budget.budgetId(), lineId, new BigDecimal("650.005")))
                .isInstanceOf(InvalidBudgetException.class);

        var persistedLimit = (BigDecimal) readLineRows(budget.budgetId()).get(0).get("limit_amount");
        assertThat(persistedLimit).isEqualByComparingTo("500.00");
    }

    // --------------------------------------------------------- cross-tenant isolation

    @Test
    @DisplayName("updating a line item on another user's budget is not found, not a silent no-op")
    void updatingAnotherUsersLineIsNotFound() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();
        actAs(UUID.randomUUID());

        assertThatThrownBy(() -> budgetLineService.updateLineItemLimit(
                userId, budget.budgetId(), lineId, new BigDecimal("600.00")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("removing a line item from another user's budget is not found, not a silent no-op")
    void removingAnotherUsersLineIsNotFound() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();
        actAs(UUID.randomUUID());

        assertThatThrownBy(() -> budgetLineService.removeLineItem(userId, budget.budgetId(), lineId))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(countLineRows(budget.budgetId())).isEqualTo(1);
    }

    // --------------------------------------------------------- category-uniqueness scoping

    @Test
    @DisplayName("the same category name is allowed on two different budget periods for the same user")
    void sameCategoryAllowedAcrossDifferentBudgetPeriods() {
        var januaryBudget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Travel", "200.00")));
        var februaryBudget = budgetService.upsertBudget(userId, currentMonth().plusMonths(1), null, List.of());

        BudgetLine added = budgetLineService.addLineItem(
                userId, februaryBudget.budgetId(), new BudgetLineInput("Travel", new BigDecimal("300.00")));

        assertThat(added.category()).isEqualTo("Travel");
        assertThat(countLineRows(januaryBudget.budgetId())).isEqualTo(1);
        assertThat(countLineRows(februaryBudget.budgetId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a category freed up by removal can be re-added within the same budget")
    void categoryCanBeReAddedAfterRemoval() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Dining", "100.00")));
        var lineId = budget.lines().get(0).lineId();
        budgetLineService.removeLineItem(userId, budget.budgetId(), lineId);

        BudgetLine readded = budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("Dining", new BigDecimal("150.00")));

        assertThat(readded.lineId()).isNotEqualTo(lineId);
        assertThat(countLineRows(budget.budgetId())).isEqualTo(1);
    }
}
