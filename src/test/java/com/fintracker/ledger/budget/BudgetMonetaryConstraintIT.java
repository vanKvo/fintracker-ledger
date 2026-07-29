package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import com.fintracker.ledger.budget.model.BudgetLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-5.1 B. Constraints — Range Constraint for limitAmount, and the Monetary Precision & Scale
 * Constraint.
 *
 * <p>Both boundaries are stated inclusively and exactly, so both are asserted at the boundary
 * rather than near it. The scale rule is the sharper of the two: it forbids <i>silent rounding</i>,
 * which is the natural thing to reach for and is precisely what the requirement rules out — a
 * financial system that quietly turns 10.005 into 10.01 has invented money the user never entered.
 */
class BudgetMonetaryConstraintIT extends AbstractBudgetIT {

    // REQ-5.1 Range Constraint: "greater than or equal to 0.00". Zero is a legitimate ceiling —
    // it means "budget nothing for this category", not "no budget".
    @Test
    @DisplayName("a limit of exactly 0.00 is accepted")
    void zeroLimitIsAccepted() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Dining", "0.00")));

        assertThat(budget.lines()).singleElement()
                .satisfies(l -> assertThat(l.limitAmount()).isEqualByComparingTo("0.00"));
    }

    // REQ-5.1 Range Constraint: one cent below the lower bound.
    @Test
    @DisplayName("a negative limit is rejected")
    void negativeLimitIsRejected() {
        assertThatThrownBy(() ->
                budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Dining", "-0.01"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    // REQ-5.1 Range Constraint: "less than or equal to 999,999,999.99" — the bound itself is legal.
    @Test
    @DisplayName("a limit of exactly 999999999.99 is accepted")
    void upperBoundIsInclusive() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null,
                List.of(line("Housing", "999999999.99")));

        assertThat(budget.lines()).singleElement()
                .satisfies(l -> assertThat(l.limitAmount()).isEqualByComparingTo("999999999.99"));
    }

    // REQ-5.1 Range Constraint: one cent past the inclusive bound.
    @Test
    @DisplayName("a limit one cent above the upper bound is rejected")
    void oneCentAboveTheUpperBoundIsRejected() {
        assertThatThrownBy(() ->
                budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Housing", "1000000000.00"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    // REQ-5.1 Monetary Precision & Scale Constraint: "Any payload containing a monetary amount
    // with more than 2 decimal places (e.g., 10.005) shall be rejected immediately ... and must
    // not be silently rounded by the backend."
    @Test
    @DisplayName("a limit with three decimal places is rejected rather than rounded")
    void threeDecimalPlacesIsRejected() {
        assertThatThrownBy(() ->
                budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Dining", "10.005"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    // REQ-5.1 Monetary Precision & Scale Constraint — the rule is about the scale of the submitted
    // amount, not about whether the extra digits happen to be significant. 100.000 is exactly one
    // hundred and still carries three decimal places, so it is rejected. This separates a genuine
    // scale check from a value-equality check such as `amount.setScale(2) == amount`.
    @Test
    @DisplayName("a limit with three decimal places is rejected even when the extra digits are zeros")
    void threeDecimalPlacesIsRejectedEvenWhenExact() {
        assertThatThrownBy(() ->
                budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Dining", "100.000"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    // REQ-5.1 Monetary Precision & Scale Constraint: "a maximum scale of 2" — fewer than two
    // decimal places is under the maximum, not a violation of it.
    @Test
    @DisplayName("a limit with one decimal place is accepted")
    void fewerThanTwoDecimalPlacesIsAccepted() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Dining", "100.0")));

        assertThat(budget.lines()).singleElement()
                .satisfies(l -> assertThat(l.limitAmount()).isEqualByComparingTo("100.0"));
    }

    // REQ-5.1 Monetary Precision & Scale Constraint — an accepted amount reaches the column with
    // its value intact.
    @Test
    @DisplayName("an accepted limit is persisted at its exact value")
    void acceptedLimitIsPersistedExactly() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Dining", "123.45")));

        assertThat(readLineRows(budget.budgetId())).singleElement()
                .satisfies(row -> assertThat(row.get("limit_amount")).hasToString("123.45"));
    }

    // REQ-5.1 Range Constraint — limitAmount is non-nullable on BudgetLine; a null must surface as
    // the documented validation failure rather than a NullPointerException or a database error.
    @Test
    @DisplayName("a null limit is rejected as a validation failure")
    void nullLimitIsRejected() {
        var nullLimit = new BudgetLine(null, null, "Dining", null, null, null);

        assertThatThrownBy(() -> budgetService.upsertBudget(userId, currentMonth(), null, List.of(nullLimit)))
                .isInstanceOf(InvalidBudgetException.class);
    }

    // REQ-5.1 Range Constraint — a value far above the bound must be caught by the application
    // rule, not left to the DECIMAL(15,2) column to reject with a database error.
    @Test
    @DisplayName("a wildly out-of-range limit is rejected by the application, not by the database")
    void grosslyOutOfRangeLimitIsRejectedByTheApplication() {
        var huge = new BudgetLine(null, null, "Housing", new BigDecimal("99999999999999.99"), null, null);

        assertThatThrownBy(() -> budgetService.upsertBudget(userId, currentMonth(), null, List.of(huge)))
                .isInstanceOf(InvalidBudgetException.class);
    }
}
