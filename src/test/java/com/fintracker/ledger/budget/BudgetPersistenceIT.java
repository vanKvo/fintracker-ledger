package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-5.1 C. Data Impacts — the {@code status} column and its CHECK constraint, and the
 * write/replace semantics of an upsert against {@code ledger.budgets} / {@code ledger.budget_lines}.
 *
 * <p>Every assertion here reads the tables directly rather than through the repository, so a
 * symmetric bug in the read path cannot hide a bug in the write path. The state-after-failure
 * tests are the ones worth the setup cost: a validation error that has already written half a
 * payload leaves a user's budget in a state they never asked for and cannot see.
 */
class BudgetPersistenceIT extends AbstractBudgetIT {

    // REQ-5.1 C. Data Impacts: "Add new column name 'status' in the existing Budget table with
    // check constraint that allows either 'ACTIVE' or 'CLOSED'." Enforced by the database, so that
    // a code path bypassing the service cannot invent a third state.
    @Test
    @DisplayName("the database rejects a status value outside ACTIVE and CLOSED")
    void statusColumnRejectsValuesOutsideTheAllowedSet() {
        assertThatThrownBy(() -> executeAsSuperuser("""
                INSERT INTO ledger.budgets (budget_id, user_id, effective_month, version, status)
                VALUES (?, ?, ?, 1, 'ARCHIVED')
                """, UUID.randomUUID(), userId, currentMonth()))
                .hasCauseInstanceOf(SQLException.class);
    }

    // REQ-5.1 State Initialization, asserted at the column: a budget created through the service
    // is ACTIVE in storage, not merely ACTIVE in the object the service happened to return.
    @Test
    @DisplayName("a newly created budget is stored with status ACTIVE")
    void newBudgetIsStoredAsActive() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));

        assertThat(readStatusColumn(budget.budgetId())).isEqualTo("ACTIVE");
    }

    // REQ-5.1 Manual Close — the transition is durable, not in-memory.
    @Test
    @DisplayName("closeBudget persists CLOSED to the status column")
    void closeIsPersisted() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));

        budgetService.closeBudget(userId, budget.budgetId());

        assertThat(readStatusColumn(budget.budgetId())).isEqualTo("CLOSED");
    }

    // REQ-5.1 D. REST API Mapping: "PUT /api/v1/budgets — Create or update budget", and the
    // interface contract "List of category limits to establish or overwrite budget lines". An
    // upsert states the budget's whole line set, so a category absent from the payload is gone.
    @Test
    @DisplayName("an upsert replaces the line set rather than accumulating onto it")
    void upsertReplacesTheLineSet() {
        var month = currentMonth();
        var created = budgetService.upsertBudget(userId, month, null,
                List.of(line("Groceries", "500.00"), line("Dining", "200.00")));

        budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "550.00")));

        assertThat(readLineRows(created.budgetId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("category")).isEqualTo("Groceries");
                    assertThat(row.get("limit_amount")).hasToString("550.00");
                });
    }

    // REQ-5.1 Constraints, state after failure: a payload rejected for one bad line must not have
    // written the good ones first. Validating inside the write loop passes every happy-path test
    // and fails this one.
    @Test
    @DisplayName("an update rejected for one invalid line leaves the previously stored lines untouched")
    void rejectedUpdateLeavesPreviousLinesIntact() {
        var month = currentMonth();
        var created = budgetService.upsertBudget(userId, month, null,
                List.of(line("Groceries", "500.00"), line("Dining", "200.00")));

        var payload = new ArrayList<>(distinctLines(30));
        payload.add(line("Housing", "-1.00"));
        assertThatThrownBy(() -> budgetService.upsertBudget(userId, month, null, payload))
                .isInstanceOf(InvalidBudgetException.class);

        assertThat(readLineRows(created.budgetId()))
                .as("the original two lines, unchanged and un-supplemented")
                .hasSize(2)
                .anySatisfy(row -> {
                    assertThat(row.get("category")).isEqualTo("Groceries");
                    assertThat(row.get("limit_amount")).hasToString("500.00");
                })
                .anySatisfy(row -> {
                    assertThat(row.get("category")).isEqualTo("Dining");
                    assertThat(row.get("limit_amount")).hasToString("200.00");
                });
    }

    // REQ-5.1 Constraints, state after failure on the creation path: a rejected create leaves no
    // budget row and therefore no lines — the user's next request must not find a phantom budget.
    @Test
    @DisplayName("a rejected creation persists neither a budget row nor any line rows")
    void rejectedCreationPersistsNothing() {
        var payload = new ArrayList<>(distinctLines(10));
        payload.add(line("Housing", "10.005"));

        assertThatThrownBy(() -> budgetService.upsertBudget(userId, currentMonth(), null, payload))
                .isInstanceOf(InvalidBudgetException.class);

        assertThat(countBudgetRows(userId)).isZero();
    }

    // REQ-5.1 Template Inheritance — an empty budget is a real, persisted budget with no lines,
    // not the absence of a budget.
    @Test
    @DisplayName("an empty budget is stored as a budget row with zero line rows")
    void emptyBudgetIsStoredWithNoLines() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of());

        assertThat(countBudgetRows(userId)).isEqualTo(1);
        assertThat(countLineRows(budget.budgetId())).isZero();
        assertThat(readStatusColumn(budget.budgetId())).isEqualTo("ACTIVE");
    }
}
