package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import com.fintracker.ledger.budget.exception.LineItemLimitExceededException;
import com.fintracker.ledger.budget.model.BudgetStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-5.1 B. Constraints — the 50-line ceiling, category uniqueness within a payload, and the
 * empty-budget case from Template Inheritance.
 *
 * <p>The ceiling is asserted at 50 and at 51 rather than at some comfortable distance, because
 * "maximum is 50" is ambiguous by exactly one row to anyone who does not read it carefully.
 */
class BudgetCapacityAndUniquenessIT extends AbstractBudgetIT {

    // REQ-5.1 Constraints: "Maximum active budget line items per budget is 50." Fifty is the
    // maximum permitted, so fifty must succeed.
    @Test
    @DisplayName("a budget with exactly 50 line items is accepted")
    void exactlyFiftyLinesIsAccepted() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, distinctLines(50));

        assertThat(budget.lines()).hasSize(50);
        assertThat(countLineRows(budget.budgetId())).isEqualTo(50);
    }

    // REQ-5.1 Constraints + Error Mappings: one past the ceiling raises
    // LineItemLimitExceededException (400 Bad Request).
    @Test
    @DisplayName("a budget with 51 line items is rejected with LineItemLimitExceededException")
    void fiftyOneLinesIsRejected() {
        assertThatThrownBy(() -> budgetService.upsertBudget(userId, currentMonth(), null, distinctLines(51)))
                .isInstanceOf(LineItemLimitExceededException.class);
    }

    // REQ-5.1 Constraints: "The payload must not contain duplicate category names for the same
    // budget ID." Also backed by UNIQUE (budget_id, category) — but the application must reject it
    // as a validation failure rather than letting the constraint surface as a database error.
    @Test
    @DisplayName("a payload containing the same category twice is rejected")
    void duplicateCategoryInPayloadIsRejected() {
        var payload = List.of(line("Groceries", "100.00"), line("Groceries", "200.00"));

        assertThatThrownBy(() -> budgetService.upsertBudget(userId, currentMonth(), null, payload))
                .isInstanceOf(InvalidBudgetException.class);
    }

    // REQ-5.1 Category Uniqueness, read together with REQ-5.2's "Category matching shall be
    // case-insensitive": "Groceries" and "groceries" are one category, so a payload carrying both
    // is a duplicate. A case-sensitive check accepts this and leaves two competing ceilings on the
    // same spending.
    @Test
    @DisplayName("categories differing only in case are treated as duplicates")
    void categoriesDifferingOnlyByCaseAreDuplicates() {
        var payload = List.of(line("Groceries", "100.00"), line("groceries", "200.00"));

        assertThatThrownBy(() -> budgetService.upsertBudget(userId, currentMonth(), null, payload))
                .isInstanceOf(InvalidBudgetException.class);
    }

    // REQ-5.1 Category Uniqueness — a category name is the identity of a line, so a blank one
    // cannot be stored. (ledger.budget_lines.category is NOT NULL but does not forbid whitespace.)
    @Test
    @DisplayName("a blank category name is rejected")
    void blankCategoryIsRejected() {
        assertThatThrownBy(() ->
                budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("   ", "100.00"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    // REQ-5.1 Constraints — a payload that breaks two rules at once must still be rejected as one
    // of the two documented failures, and must leave nothing behind. Which of the two wins is not
    // pinned by the specification, so this asserts the part that is: rejection, and no partial
    // write.
    @Test
    @DisplayName("a payload that is both oversized and duplicated is rejected and persists nothing")
    void payloadViolatingTwoConstraintsIsRejectedWithoutPersisting() {
        var payload = new ArrayList<>(distinctLines(51));
        payload.set(50, line("Category-0", "10.00"));

        assertThatThrownBy(() -> budgetService.upsertBudget(userId, currentMonth(), null, payload))
                .isInstanceOfAny(LineItemLimitExceededException.class, InvalidBudgetException.class);

        assertThat(countBudgetRows(userId))
                .as("a rejected creation must not leave a budget row behind")
                .isZero();
    }

    // REQ-5.1 Template Inheritance: "If no template is selected and the user choose to create a
    // budget from scratch, an empty budget with no line items (lines = []) shall be created with
    // status = 'ACTIVE'."
    @Test
    @DisplayName("with no template and no lines, an empty ACTIVE budget is created")
    void emptyPayloadCreatesAnEmptyActiveBudget() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        assertThat(budget.status()).isEqualTo(BudgetStatus.ACTIVE);
        assertThat(budget.lines()).isEmpty();
        assertThat(budget.budgetId()).isNotNull();
        assertThat(countBudgetRows(userId)).isEqualTo(1);
    }
}
