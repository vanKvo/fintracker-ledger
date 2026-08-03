package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.dto.BudgetLineInput;
import com.fintracker.ledger.budget.exception.DuplicateCategoryException;
import com.fintracker.ledger.budget.exception.HistoricalBudgetException;
import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import com.fintracker.ledger.budget.exception.LineItemLimitExceededException;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-5.2 "Manage Budget Line Items": granular add / update-limit / remove operations against a
 * single budget's lines, as opposed to REQ-5.1's whole-budget {@code upsertBudget} replace.
 */
class BudgetLineServiceIT extends AbstractBudgetIT {

    // ------------------------------------------------------------------ addLineItem

    @Test
    @DisplayName("adding a line item persists it under the budget and computes its spentAmount")
    void addLineItemPersistsAndComputesSpend() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Dining", "-25.00", month.plusDays(2));
        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));

        BudgetLine added = budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("Dining", new BigDecimal("150.00")));

        assertThat(added.lineId()).isNotNull();
        assertThat(added.category()).isEqualTo("Dining");
        assertThat(added.limitAmount()).isEqualByComparingTo("150.00");
        assertThat(added.spentAmount()).isEqualByComparingTo("25.00");
        assertThat(countLineRows(budget.budgetId())).isEqualTo(2);
    }

    @Test
    @DisplayName("adding a line for a future period initializes spentAmount to 0.00")
    void addLineItemForFuturePeriodInitializesSpendToZero() {
        var budget = budgetService.upsertBudget(userId, futureMonth(), null, List.of());

        BudgetLine added = budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("Travel", new BigDecimal("300.00")));

        assertThat(added.spentAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a duplicate category (case-insensitive) is rejected with DuplicateCategoryException")
    void duplicateCategoryIsRejectedCaseInsensitively() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));

        assertThatThrownBy(() -> budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("groceries", new BigDecimal("10.00"))))
                .isInstanceOf(DuplicateCategoryException.class);
    }

    @Test
    @DisplayName("adding a line item beyond the 50-item ceiling is rejected")
    void addingBeyondCeilingIsRejected() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, distinctLines(50));

        assertThatThrownBy(() -> budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("One-Too-Many", new BigDecimal("10.00"))))
                .isInstanceOf(LineItemLimitExceededException.class);
    }

    @Test
    @DisplayName("an out-of-range limitAmount is rejected")
    void outOfRangeLimitIsRejected() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        assertThatThrownBy(() -> budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("Rent", new BigDecimal("-1.00"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    @Test
    @DisplayName("a limitAmount with more than 2 decimal places is rejected, not rounded")
    void overPreciseLimitIsRejectedNotRounded() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        assertThatThrownBy(() -> budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("Rent", new BigDecimal("10.005"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    @Test
    @DisplayName("a category longer than 50 characters is truncated, not rejected")
    void overlongCategoryIsTruncated() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());
        String longCategory = "X".repeat(80);

        BudgetLine added = budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput(longCategory, new BigDecimal("10.00")));

        assertThat(added.category()).hasSize(50);
    }

    @Test
    @DisplayName("adding a line item to a CLOSED budget is rejected with HistoricalBudgetException")
    void addingToClosedBudgetIsRejected() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());
        budgetService.closeBudget(userId, budget.budgetId());

        assertThatThrownBy(() -> budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("Rent", new BigDecimal("10.00"))))
                .isInstanceOf(HistoricalBudgetException.class);
    }

    @Test
    @DisplayName("adding a line item to another user's budget is not found")
    void addingToAnotherUsersBudgetIsNotFound() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());
        actAs(UUID.randomUUID());

        assertThatThrownBy(() -> budgetLineService.addLineItem(
                userId, budget.budgetId(), new BudgetLineInput("Rent", new BigDecimal("10.00"))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------ updateLineItemLimit

    @Test
    @DisplayName("updating a line item's limit persists the new value and recomputes spentAmount")
    void updateLineItemLimitPersistsAndRecomputesSpend() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Groceries", "-80.00", month.plusDays(1));
        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();

        BudgetLine updated = budgetLineService.updateLineItemLimit(userId, budget.budgetId(), lineId, new BigDecimal("650.00"));

        assertThat(updated.limitAmount()).isEqualByComparingTo("650.00");
        assertThat(updated.spentAmount()).isEqualByComparingTo("80.00");
        var persistedLimit = (BigDecimal) readLineRows(budget.budgetId()).get(0).get("limit_amount");
        assertThat(persistedLimit).isEqualByComparingTo("650.00");
    }

    @Test
    @DisplayName("updating a non-existent line item is not found")
    void updatingUnknownLineIsNotFound() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        assertThatThrownBy(() -> budgetLineService.updateLineItemLimit(
                userId, budget.budgetId(), UUID.randomUUID(), new BigDecimal("10.00")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updating a line item on a CLOSED budget is rejected with HistoricalBudgetException")
    void updatingLineOnClosedBudgetIsRejected() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();
        budgetService.closeBudget(userId, budget.budgetId());

        assertThatThrownBy(() -> budgetLineService.updateLineItemLimit(
                userId, budget.budgetId(), lineId, new BigDecimal("600.00")))
                .isInstanceOf(HistoricalBudgetException.class);
    }

    // ------------------------------------------------------------------ removeLineItem

    @Test
    @DisplayName("removing a line item deletes it but leaves the underlying transactions untouched")
    void removingLineItemDeletesLineOnly() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Groceries", "-80.00", month.plusDays(1));
        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();

        budgetLineService.removeLineItem(userId, budget.budgetId(), lineId);

        assertThat(countLineRows(budget.budgetId())).isZero();
        var rows = queryAsSuperuser("SELECT COUNT(*) AS c FROM ledger.transactions WHERE account_id = ?", accountId);
        assertThat(((Number) rows.get(0).get("c")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("removing a line item from a CLOSED budget is rejected with HistoricalBudgetException")
    void removingLineFromClosedBudgetIsRejected() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));
        var lineId = budget.lines().get(0).lineId();
        budgetService.closeBudget(userId, budget.budgetId());

        assertThatThrownBy(() -> budgetLineService.removeLineItem(userId, budget.budgetId(), lineId))
                .isInstanceOf(HistoricalBudgetException.class);
    }

    @Test
    @DisplayName("removing a non-existent line item is not found")
    void removingUnknownLineIsNotFound() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        assertThatThrownBy(() -> budgetLineService.removeLineItem(userId, budget.budgetId(), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
