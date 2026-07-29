package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-5.1 Spend Amount Initialization: "For current or past periods, spentAmount shall
 * automatically query and sum all approved transactions matching <b>each line item's category</b>
 * within that period's date range."
 *
 * <p>"Approved" is the shape the ledger already treats as real, settled spending: status POSTED,
 * type PURCHASE, not excluded from budgeting, and not a transaction that has since been split into
 * children (whose parent would otherwise be counted alongside them). Each of those four filters
 * gets its own test, because dropping any one of them inflates a user's reported spending against
 * their own budget.
 */
class BudgetSpendEnrichmentIT extends AbstractBudgetIT {

    private static BigDecimal spentOn(Budget budget, String category) {
        return budget.lines().stream()
                .filter(l -> l.category().equalsIgnoreCase(category))
                .map(BudgetLine::spentAmount)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no budget line for category " + category));
    }

    // REQ-5.1 Spend Amount Initialization — "matching each line item's category". Two lines on the
    // same budget must report their own category's spending, not a shared month-wide total. This
    // is the single claim that separates a per-category aggregate from a per-month one.
    @Test
    @DisplayName("each line reports the spending of its own category, not a shared monthly total")
    void eachLineReportsItsOwnCategorySpending() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Groceries", "-120.00", month.plusDays(4));
        insertPostedPurchase(accountId, "Dining", "-30.00", month.plusDays(5));

        var budget = budgetService.upsertBudget(userId, month, null,
                List.of(line("Groceries", "500.00"), line("Dining", "200.00")));

        assertThat(spentOn(budget, "Groceries")).isEqualByComparingTo("120.00");
        assertThat(spentOn(budget, "Dining")).isEqualByComparingTo("30.00");
    }

    // REQ-5.1 Spend Amount Initialization — "approved transactions". A PENDING transaction has not
    // been approved yet and must not count against the budget.
    @Test
    @DisplayName("PENDING transactions are not counted")
    void pendingTransactionsAreNotCounted() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Groceries", "-100.00", month.plusDays(3));
        insertTransaction(accountId, "Groceries", "-999.00", month.plusDays(4),
                "PURCHASE", "PENDING", false, null);

        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));

        assertThat(spentOn(budget, "Groceries")).isEqualByComparingTo("100.00");
    }

    // REQ-5.1 Spend Amount Initialization — a transaction the user has explicitly excluded from
    // budgeting (is_excluded) is not spending against a ceiling.
    @Test
    @DisplayName("transactions flagged is_excluded are not counted")
    void excludedTransactionsAreNotCounted() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Groceries", "-100.00", month.plusDays(3));
        insertTransaction(accountId, "Groceries", "-999.00", month.plusDays(4),
                "PURCHASE", "POSTED", true, null);

        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));

        assertThat(spentOn(budget, "Groceries")).isEqualByComparingTo("100.00");
    }

    // REQ-5.1 Spend Amount Initialization — a CREDIT (refund) is not an expense. Counting it would
    // make a refund consume budget rather than free it.
    @Test
    @DisplayName("CREDIT transactions are not counted as spending")
    void creditTransactionsAreNotCounted() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Groceries", "-100.00", month.plusDays(3));
        insertTransaction(accountId, "Groceries", "50.00", month.plusDays(4),
                "CREDIT", "POSTED", false, null);

        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));

        assertThat(spentOn(budget, "Groceries")).isEqualByComparingTo("100.00");
    }

    // REQ-5.1 Spend Amount Initialization — once a transaction has been split, its children carry
    // the money. Counting the parent as well double-counts the same spending against the ceiling.
    @Test
    @DisplayName("a split parent is not counted alongside its children")
    void splitParentIsNotDoubleCounted() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        var parentId = insertPostedPurchase(accountId, "Groceries", "-100.00", month.plusDays(3));
        insertTransaction(accountId, "Groceries", "-60.00", month.plusDays(3), "PURCHASE", "POSTED", false, parentId);
        insertTransaction(accountId, "Groceries", "-40.00", month.plusDays(3), "PURCHASE", "POSTED", false, parentId);

        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));

        assertThat(spentOn(budget, "Groceries"))
                .as("children only (100.00), not children plus parent (200.00)")
                .isEqualByComparingTo("100.00");
    }

    // REQ-5.1 Spend Amount Initialization — spending in a category the budget does not cap is not
    // attributed to some other line.
    @Test
    @DisplayName("spending in an unbudgeted category is not attributed to another line")
    void spendingInAnUnbudgetedCategoryIsIgnored() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Groceries", "-100.00", month.plusDays(3));
        insertPostedPurchase(accountId, "Travel", "-750.00", month.plusDays(6));

        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));

        assertThat(spentOn(budget, "Groceries")).isEqualByComparingTo("100.00");
    }

    // REQ-5.1 Spend Amount Initialization — a category with no activity reports zero. spentAmount
    // is non-nullable on BudgetLine, so null here would be a schema violation, not merely untidy.
    @Test
    @DisplayName("a category with no transactions reports 0.00, never null")
    void categoryWithoutTransactionsReportsZero() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));

        assertThat(spentOn(budget, "Groceries")).isNotNull().isEqualByComparingTo("0.00");
    }

    // REQ-5.1 Spend Amount Initialization, read with REQ-5.2's case-insensitive category matching:
    // ledger.transactions.category is free text, so a budget line for "Groceries" must still match
    // spending recorded as "groceries".
    @Test
    @DisplayName("category matching between line and transaction is case-insensitive")
    void categoryMatchingIsCaseInsensitive() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "groceries", "-75.00", month.plusDays(3));

        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));

        assertThat(spentOn(budget, "Groceries")).isEqualByComparingTo("75.00");
    }

    // REQ-5.1 Spend Amount Initialization — "within that period's date range". The range covers the
    // whole calendar month inclusively at both ends; a transaction one day outside belongs to a
    // different budget.
    @Test
    @DisplayName("the period range includes the first and last day of the month and excludes its neighbours")
    void periodRangeIsInclusiveOfBothMonthEnds() {
        var month = LocalDate.of(2026, 7, 1);
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Groceries", "-10.00", LocalDate.of(2026, 6, 30));
        insertPostedPurchase(accountId, "Groceries", "-1.00", LocalDate.of(2026, 7, 1));
        insertPostedPurchase(accountId, "Groceries", "-2.00", LocalDate.of(2026, 7, 31));
        insertPostedPurchase(accountId, "Groceries", "-20.00", LocalDate.of(2026, 8, 1));

        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));

        assertThat(spentOn(budget, "Groceries"))
                .as("1.00 + 2.00 only — June 30 and August 1 belong to other months")
                .isEqualByComparingTo("3.00");
    }

    // REQ-5.1 Spend Amount Initialization — the spend figure is computed per request, never stored.
    // There is no spent_amount column, and adding one would let a stale value outlive the
    // transactions it summarizes.
    @Test
    @DisplayName("spentAmount reflects transactions added after the budget was created")
    void spentAmountIsRecomputedRatherThanStored() {
        var month = currentMonth();
        var accountId = insertAccount(userId);
        var budgetId = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")))
                .budgetId();

        insertPostedPurchase(accountId, "Groceries", "-42.00", month.plusDays(7));
        var reread = budgetService.getBudgetForMonth(userId, month);

        assertThat(reread.budgetId()).isEqualTo(budgetId);
        assertThat(spentOn(reread, "Groceries")).isEqualByComparingTo("42.00");
    }

    // REQ-5.1 Spend Amount Initialization — transactions belonging to another tenant never
    // contribute, even when the category and month line up exactly.
    @Test
    @DisplayName("another user's spending in the same category and month is not counted")
    void anotherUsersSpendingIsNotCounted() {
        var month = currentMonth();
        var otherUser = UUID.randomUUID();
        var otherAccount = insertAccount(otherUser);
        insertPostedPurchase(otherAccount, "Groceries", "-900.00", month.plusDays(3));

        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Groceries", "-100.00", month.plusDays(3));

        var budget = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));

        assertThat(spentOn(budget, "Groceries")).isEqualByComparingTo("100.00");
    }
}
