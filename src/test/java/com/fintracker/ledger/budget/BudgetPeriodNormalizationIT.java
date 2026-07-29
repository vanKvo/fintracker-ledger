package com.fintracker.ledger.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-5.1 B. Constraints — Normalization Rule, and the clock-dependent half of Spend Amount
 * Initialization.
 *
 * <p>Normalization is what makes "one budget per user per month" true at all: the month is the
 * identity of a budget, so a request naming any day within a month must resolve to the same row.
 * The clock tests pin the current/future boundary to the exact second, which is the only way to
 * show that period classification comes from an injected clock rather than the wall clock.
 */
class BudgetPeriodNormalizationIT extends AbstractBudgetIT {

    // REQ-5.1 Normalization Rule: "The month must be normalized to the first day of the calendar
    // month (YYYY-MM-01) ... before persistence."
    @Test
    @DisplayName("a mid-month date is normalized to the first of that month")
    void midMonthDateNormalizesToFirstOfMonth() {
        var budget = budgetService.upsertBudget(userId, LocalDate.of(2026, 3, 17), null,
                List.of(line("Groceries", "500.00")));

        assertThat(budget.effectiveMonth()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    // REQ-5.1 Normalization Rule + Period Selection & Upsert Behavior: because both requests
    // normalize to the same month, the second is an update of the first — not a second budget.
    @Test
    @DisplayName("two different days in the same month address one and the same budget")
    void twoDaysInTheSameMonthResolveToOneBudget() {
        var first = budgetService.upsertBudget(userId, LocalDate.of(2026, 3, 17), null,
                List.of(line("Groceries", "500.00")));
        var second = budgetService.upsertBudget(userId, LocalDate.of(2026, 3, 28), null,
                List.of(line("Groceries", "600.00")));

        assertThat(second.budgetId()).isEqualTo(first.budgetId());
        assertThat(countBudgetRows(userId)).isEqualTo(1);
        assertThat(second.lines()).singleElement()
                .satisfies(l -> assertThat(l.limitAmount()).isEqualByComparingTo("600.00"));
    }

    // REQ-5.1 Normalization Rule — 29 February exists only in leap years; normalizing it must not
    // depend on arithmetic that assumes a fixed month length.
    @Test
    @DisplayName("a leap day normalizes to the first of February")
    void leapDayNormalizesToFirstOfFebruary() {
        var budget = budgetService.upsertBudget(userId, LocalDate.of(2028, 2, 29), null,
                List.of(line("Groceries", "500.00")));

        assertThat(budget.effectiveMonth()).isEqualTo(LocalDate.of(2028, 2, 1));
    }

    // REQ-5.1 Normalization Rule: "...before persistence." Asserted against the column itself, so
    // that normalizing only on the way out of the service would not satisfy it.
    @Test
    @DisplayName("the persisted effective_month column holds the first of the month")
    void persistedEffectiveMonthIsTheFirstOfTheMonth() {
        var budget = budgetService.upsertBudget(userId, LocalDate.of(2026, 3, 17), null,
                List.of(line("Groceries", "500.00")));

        var rows = queryAsSuperuser(
                "SELECT effective_month FROM ledger.budgets WHERE budget_id = ?", budget.budgetId());

        assertThat(rows).singleElement()
                .satisfies(row -> assertThat(row.get("effective_month")).hasToString("2026-03-01"));
    }

    // REQ-5.1 Spend Amount Initialization: "For future periods, spentAmount shall initialize to
    // $0.00." One second before August begins, August is still a future period — even though
    // transactions dated in August already exist.
    @Test
    @DisplayName("at 23:59:59 on the last day of July, August is still a future period and spends nothing")
    void nextMonthIsFutureUntilTheInstantItBegins() {
        clock.setTo(Instant.parse("2026-07-31T23:59:59Z"));
        var august = LocalDate.of(2026, 8, 1);
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Groceries", "-120.00", LocalDate.of(2026, 8, 10));
        budgetService.upsertBudget(userId, august, null, List.of(line("Groceries", "500.00")));

        var budget = budgetService.getBudgetForMonth(userId, august);

        assertThat(budget.lines()).singleElement()
                .satisfies(l -> assertThat(l.spentAmount()).isEqualByComparingTo("0.00"));
    }

    // REQ-5.1 Spend Amount Initialization — the same fixture one second later. August is now the
    // current period, so its transactions count. Any implementation reading the wall clock instead
    // of the injected one cannot satisfy both this test and the previous one.
    @Test
    @DisplayName("one second later, August is the current period and its spending is summed")
    void theSameMonthBecomesCurrentAtMidnight() {
        clock.setTo(Instant.parse("2026-08-01T00:00:00Z"));
        var august = LocalDate.of(2026, 8, 1);
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Groceries", "-120.00", LocalDate.of(2026, 8, 10));
        budgetService.upsertBudget(userId, august, null, List.of(line("Groceries", "500.00")));

        var budget = budgetService.getBudgetForMonth(userId, august);

        assertThat(budget.lines()).singleElement()
                .satisfies(l -> assertThat(l.spentAmount()).isEqualByComparingTo("120.00"));
    }

    // REQ-5.1 Automated Period Closure across a year boundary: December is before January of the
    // following year. Comparing month-of-year numbers rather than dates fails here.
    @Test
    @DisplayName("closePastBudgets treats December as before the following January")
    void closurePastBudgetsHandlesTheYearBoundary() {
        clock.setTo(LocalDate.of(2027, 1, 10));
        var december = budgetService.upsertBudget(userId, LocalDate.of(2026, 12, 1), null,
                List.of(line("Groceries", "100.00")));
        var january = budgetService.upsertBudget(userId, LocalDate.of(2027, 1, 1), null,
                List.of(line("Groceries", "100.00")));

        budgetService.closePastBudgets(LocalDate.of(2027, 1, 1));

        assertThat(readStatusColumn(december.budgetId())).isEqualTo("CLOSED");
        assertThat(readStatusColumn(january.budgetId())).isEqualTo("ACTIVE");
    }
}
