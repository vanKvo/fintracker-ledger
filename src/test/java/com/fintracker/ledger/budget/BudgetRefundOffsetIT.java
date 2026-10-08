package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TXT-02: a category's spend is its EXPENSE total minus its REFUND total for the month the refund
 * lands in. TRANSFER, INCOME and ADJUSTMENT never count. Net spend may go negative — reported as-is.
 */
class BudgetRefundOffsetIT extends AbstractBudgetIT {

    private static BigDecimal spentOn(Budget budget, String category) {
        return budget.lines().stream()
                .filter(l -> l.category().equalsIgnoreCase(category))
                .map(BudgetLine::spentAmount)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no budget line for category " + category));
    }

    @Test
    @DisplayName("a refund offsets expenses in its category: $150 expense - $50 refund = $100")
    void refundOffsetsExpenseInSameCategory() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedExpense(accountId, "Shopping", "-150.00", month.plusDays(2));
        insertTransaction(accountId, "Shopping", "50.00", month.plusDays(5), "REFUND", "POSTED", false, null);

        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Shopping", "500.00")));

        assertThat(spentOn(budget, "Shopping")).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("refunds larger than expenses report a negative net spend")
    void refundsExceedingExpensesReportNegativeSpend() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedExpense(accountId, "Shopping", "-20.00", month.plusDays(2));
        insertTransaction(accountId, "Shopping", "50.00", month.plusDays(5), "REFUND", "POSTED", false, null);

        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Shopping", "500.00")));

        assertThat(spentOn(budget, "Shopping")).isEqualByComparingTo("-30.00");
    }

    @Test
    @DisplayName("a refund offsets the month it lands in, not the month of the original purchase")
    void refundOffsetsTheMonthItLandsIn() {
        var purchaseMonth = pastMonth();
        var refundMonth = purchaseMonth.plusMonths(1);
        var accountId = insertAccount(userId);
        insertPostedExpense(accountId, "Shopping", "-150.00", purchaseMonth.plusDays(2));
        insertTransaction(accountId, "Shopping", "50.00", refundMonth.plusDays(1), "REFUND", "POSTED", false, null);

        var purchaseBudget = budgetService.upsertBudget(userId, purchaseMonth, null, List.of(line("Shopping", "500.00")));
        var refundBudget = budgetService.upsertBudget(userId, refundMonth, null, List.of(line("Shopping", "500.00")));

        assertThat(spentOn(purchaseBudget, "Shopping")).isEqualByComparingTo("150.00");
        assertThat(spentOn(refundBudget, "Shopping")).isEqualByComparingTo("-50.00");
    }

    @Test
    @DisplayName("TRANSFER, INCOME and ADJUSTMENT transactions contribute nothing to spending")
    void nonSpendingTypesAreNotCounted() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedExpense(accountId, "Shopping", "-100.00", month.plusDays(2));
        insertTransaction(accountId, "Shopping", "-400.00", month.plusDays(3), "TRANSFER", "POSTED", false, null);
        insertTransaction(accountId, "Shopping", "900.00", month.plusDays(4), "INCOME", "POSTED", false, null);
        insertTransaction(accountId, "Shopping", "-7.00", month.plusDays(5), "ADJUSTMENT", "POSTED", false, null);

        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Shopping", "500.00")));

        assertThat(spentOn(budget, "Shopping")).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a refund in another category does not offset this category")
    void refundOnlyOffsetsItsOwnCategory() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedExpense(accountId, "Shopping", "-100.00", month.plusDays(2));
        insertTransaction(accountId, "Travel", "60.00", month.plusDays(3), "REFUND", "POSTED", false, null);

        var budget = budgetService.upsertBudget(userId, month, null,
                List.of(line("Shopping", "500.00"), line("Travel", "500.00")));

        assertThat(spentOn(budget, "Shopping")).isEqualByComparingTo("100.00");
        assertThat(spentOn(budget, "Travel")).isEqualByComparingTo("-60.00");
    }

    @Test
    @DisplayName("a PENDING refund does not offset spending")
    void pendingRefundIsNotCounted() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedExpense(accountId, "Shopping", "-100.00", month.plusDays(2));
        insertTransaction(accountId, "Shopping", "40.00", month.plusDays(3), "REFUND", "PENDING", false, null);

        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Shopping", "500.00")));

        assertThat(spentOn(budget, "Shopping")).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("the yearly budget listing applies the same refund offset")
    void yearListingAppliesRefundOffset() {
        var month = pastMonth();
        var accountId = insertAccount(userId);
        insertPostedExpense(accountId, "Shopping", "-150.00", month.plusDays(2));
        insertTransaction(accountId, "Shopping", "50.00", month.plusDays(5), "REFUND", "POSTED", false, null);
        budgetService.upsertBudget(userId, month, null, List.of(line("Shopping", "500.00")));

        var listed = budgetService.getBudgetsForYear(userId, month.getYear()).stream()
                .filter(b -> b.effectiveMonth().equals(month))
                .findFirst()
                .orElseThrow();

        assertThat(spentOn(listed, "Shopping")).isEqualByComparingTo("100.00");
    }
}
