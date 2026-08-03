package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.exception.HistoricalBudgetException;
import com.fintracker.ledger.budget.model.BudgetStatus;
import com.fintracker.ledger.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-5.1 lifecycle: State Initialization, Modification Guard, Manual Close, Automated Period
 * Closure, Immutability upon Closure, Reopening Exemption.
 *
 * <p>The central claim under test is that write permission is governed by <b>status</b> and
 * nothing else. A budget's month determines when the scheduler will close it; it never by itself
 * makes the budget read-only. That distinction is the most likely thing for an implementation to
 * get wrong, because "past month" and "closed" coincide for almost every budget in production.
 */
class BudgetLifecycleIT extends AbstractBudgetIT {

    // REQ-5.1 State Initialization: "All newly created budgets, whether for past, present, or
    // future periods, shall initialize with status = 'ACTIVE'."
    @Test
    @DisplayName("a budget created for the current month initializes ACTIVE")
    void currentMonthBudgetInitializesActive() {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Groceries", "500.00")));

        assertThat(budget.status()).isEqualTo(BudgetStatus.ACTIVE);
    }

    // REQ-5.1 State Initialization + Period Selection: creating for a past period is explicitly
    // permitted, and the result is ACTIVE like any other — not born CLOSED.
    @Test
    @DisplayName("a budget created for a past period initializes ACTIVE")
    void pastPeriodBudgetInitializesActive() {
        var budget = budgetService.upsertBudget(userId, pastMonth(), null, List.of(line("Groceries", "500.00")));

        assertThat(budget.status()).isEqualTo(BudgetStatus.ACTIVE);
    }

    // REQ-5.1 Modification Guard: "Operations (creation or updates) on budgets marked as 'ACTIVE'
    // are permitted." A past-period budget that is still ACTIVE is therefore writable — the guard
    // keys off status, not off the month having elapsed.
    @Test
    @DisplayName("an ACTIVE budget for a past period can still be updated")
    void activePastPeriodBudgetRemainsWritable() {
        var month = pastMonth();
        budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));

        var updated = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "650.00")));

        assertThat(updated.status()).isEqualTo(BudgetStatus.ACTIVE);
        assertThat(updated.lines()).singleElement()
                .satisfies(l -> assertThat(l.limitAmount()).isEqualByComparingTo("650.00"));
    }

    @Test
    @DisplayName("a budget created for a future period initializes ACTIVE")
    void futurePeriodBudgetInitializesActive() {
        var budget = budgetService.upsertBudget(userId, futureMonth(), null, List.of(line("Travel", "1200.00")));

        assertThat(budget.status()).isEqualTo(BudgetStatus.ACTIVE);
    }

    // REQ-5.1 Manual Close: "A user or system actor can explicitly close an active budget prior to
    // month-end via the close endpoint/method."
    @Test
    @DisplayName("closeBudget transitions ACTIVE to CLOSED")
    void closeTransitionsActiveToClosed() {
        var created = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Dining", "200.00")));

        var closed = budgetService.closeBudget(userId, created.budgetId());

        assertThat(closed.status()).isEqualTo(BudgetStatus.CLOSED);
        assertThat(closed.budgetId()).isEqualTo(created.budgetId());
    }

    // REQ-5.1 E. Interface Details, closeBudget: "@throws HistoricalBudgetException If the budget
    // is already CLOSED."
    @Test
    @DisplayName("closing an already-CLOSED budget is rejected with HistoricalBudgetException")
    void closingAnAlreadyClosedBudgetIsRejected() {
        var created = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Dining", "200.00")));
        budgetService.closeBudget(userId, created.budgetId());

        assertThatThrownBy(() -> budgetService.closeBudget(userId, created.budgetId()))
                .isInstanceOf(HistoricalBudgetException.class);
    }

    // REQ-5.1 Immutability upon Closure: "Once status == 'CLOSED', all subsequent write operations
    // (upsertBudget, ...) are blocked and throw HistoricalBudgetException."
    @Test
    @DisplayName("upserting into a CLOSED budget is rejected with HistoricalBudgetException")
    void upsertIntoClosedBudgetIsRejected() {
        var month = currentMonth();
        var created = budgetService.upsertBudget(userId, month, null, List.of(line("Dining", "200.00")));
        budgetService.closeBudget(userId, created.budgetId());

        assertThatThrownBy(() ->
                budgetService.upsertBudget(userId, month, null, List.of(line("Dining", "999.00"))))
                .isInstanceOf(HistoricalBudgetException.class);
    }

    // REQ-5.1 Immutability upon Closure — the rejection must also be effective, not merely thrown:
    // a blocked write leaves the stored limit untouched.
    @Test
    @DisplayName("a write rejected by the CLOSED guard leaves the stored lines unchanged")
    void rejectedWriteOnClosedBudgetChangesNothing() {
        var month = currentMonth();
        var created = budgetService.upsertBudget(userId, month, null, List.of(line("Dining", "200.00")));
        budgetService.closeBudget(userId, created.budgetId());

        assertThatThrownBy(() ->
                budgetService.upsertBudget(userId, month, null, List.of(line("Dining", "999.00"))))
                .isInstanceOf(HistoricalBudgetException.class);

        assertThat(readLineRows(created.budgetId())).singleElement()
                .satisfies(row -> assertThat(row.get("limit_amount")).hasToString("200.00"));
    }

    // REQ-5.1 Reopening Exemption: "A closed budget can only transition back to ACTIVE through an
    // explicit reopenBudget call."
    @Test
    @DisplayName("reopenBudget transitions CLOSED back to ACTIVE")
    void reopenTransitionsClosedToActive() {
        var created = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Dining", "200.00")));
        budgetService.closeBudget(userId, created.budgetId());

        var reopened = budgetService.reopenBudget(userId, created.budgetId());

        assertThat(reopened.status()).isEqualTo(BudgetStatus.ACTIVE);
    }

    // REQ-5.1 E. Interface Details, reopenBudget: ResourceNotFoundException is the *only* declared
    // failure, so reopening a budget that is already ACTIVE is a no-op rather than an error.
    // (Spec assumption C9 — recorded here because it is exactly the kind of unstated case an
    // implementation guesses wrong, usually by throwing.)
    @Test
    @DisplayName("reopening an already-ACTIVE budget is an idempotent no-op")
    void reopeningAnActiveBudgetIsANoOp() {
        var created = budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("Dining", "200.00")));

        var reopened = budgetService.reopenBudget(userId, created.budgetId());

        assertThat(reopened.status()).isEqualTo(BudgetStatus.ACTIVE);
        assertThat(reopened.budgetId()).isEqualTo(created.budgetId());
    }

    // REQ-5.1 Reopening Exemption — reopening must actually restore write permission, not just
    // flip a field.
    @Test
    @DisplayName("a reopened budget accepts writes again")
    void reopenedBudgetAcceptsWrites() {
        var month = pastMonth();
        var created = budgetService.upsertBudget(userId, month, null, List.of(line("Dining", "200.00")));
        budgetService.closeBudget(userId, created.budgetId());
        budgetService.reopenBudget(userId, created.budgetId());

        assertThatCode(() ->
                budgetService.upsertBudget(userId, month, null, List.of(line("Dining", "300.00"))))
                .doesNotThrowAnyException();
    }

    // REQ-5.1 Automated Period Closure: "all active budgets where month < current_month shall be
    // automatically transitioned from status = 'ACTIVE' to status = 'CLOSED'".
    @Test
    @DisplayName("closePastBudgets closes months strictly before the cutoff and leaves the cutoff month open")
    void closePastBudgetsClosesOnlyMonthsBeforeCutoff() {
        var elapsed = budgetService.upsertBudget(userId, currentMonth().minusMonths(1), null,
                List.of(line("Groceries", "100.00")));
        var thisMonth = budgetService.upsertBudget(userId, currentMonth(), null,
                List.of(line("Groceries", "100.00")));
        var upcoming = budgetService.upsertBudget(userId, currentMonth().plusMonths(1), null,
                List.of(line("Groceries", "100.00")));

        budgetService.closePastBudgets(currentMonth());

        assertThat(readStatusColumn(elapsed.budgetId())).isEqualTo("CLOSED");
        assertThat(readStatusColumn(thisMonth.budgetId()))
                .as("the cutoff month itself is not 'before' the cutoff")
                .isEqualTo("ACTIVE");
        assertThat(readStatusColumn(upcoming.budgetId())).isEqualTo("ACTIVE");
    }

    // REQ-5.1 Automated Period Closure — the returned count is the number actually transitioned.
    //
    // Asserted as a delta against countActiveBudgetsBefore(), not a bare "== 2": closePastBudgets
    // is a system-wide, cross-tenant scan by design (see BudgetTenancyIT.closePastBudgetsSpansAllUsers),
    // and every budget IT class shares one Testcontainers Postgres for the whole suite run
    // (AbstractIntegrationTest's singleton container, never truncated between classes). Other
    // tests elsewhere in the suite legitimately leave their own ACTIVE, past-dated budgets lying
    // around — a bare "== 2" would count those too and fail depending on run order/composition.
    @Test
    @DisplayName("closePastBudgets returns the number of budgets it transitioned")
    void closePastBudgetsReturnsTransitionCount() {
        int alreadyStale = countActiveBudgetsBefore(currentMonth());

        budgetService.upsertBudget(userId, currentMonth().minusMonths(1), null, List.of(line("A", "1.00")));
        budgetService.upsertBudget(userId, currentMonth().minusMonths(2), null, List.of(line("A", "1.00")));
        budgetService.upsertBudget(userId, currentMonth(), null, List.of(line("A", "1.00")));

        int transitioned = budgetService.closePastBudgets(currentMonth());

        assertThat(transitioned - alreadyStale)
                .as("this test's own 2 elapsed budgets, independent of any stray ACTIVE/past rows "
                        + "left behind by other tests sharing this suite's Testcontainers Postgres")
                .isEqualTo(2);
    }

    // REQ-5.1 Automated Period Closure — the scheduler fires every month and may be retried;
    // a second pass must find nothing left to do rather than re-counting rows already CLOSED.
    @Test
    @DisplayName("closePastBudgets is idempotent — a second run transitions nothing")
    void closePastBudgetsIsIdempotent() {
        var elapsed = budgetService.upsertBudget(userId, currentMonth().minusMonths(1), null,
                List.of(line("Groceries", "100.00")));
        budgetService.closePastBudgets(currentMonth());

        assertThat(budgetService.closePastBudgets(currentMonth())).isZero();
        assertThat(readStatusColumn(elapsed.budgetId())).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("reopenBudget on an unknown budget id is a 404-class failure")
    void reopeningAnUnknownBudgetIsNotFound() {
        assertThatThrownBy(() -> budgetService.reopenBudget(userId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // REQ-5.1 end to end: the whole reason "past month" and "CLOSED" must stay separate concepts.
    // An implementation that guards on the month passes several tests above and still fails here.
    @Test
    @DisplayName("full lifecycle: past-period budget is writable, then closed, then reopened, then writable again")
    void fullLifecycleRoundTrip() {
        var month = pastMonth();

        var created = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "400.00")));
        assertThat(created.status()).isEqualTo(BudgetStatus.ACTIVE);

        var edited = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "450.00")));
        assertThat(edited.lines()).singleElement()
                .satisfies(l -> assertThat(l.limitAmount()).isEqualByComparingTo("450.00"));

        budgetService.closeBudget(userId, created.budgetId());
        assertThatThrownBy(() -> budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00"))))
                .isInstanceOf(HistoricalBudgetException.class);

        budgetService.reopenBudget(userId, created.budgetId());
        var afterReopen = budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));
        assertThat(afterReopen.status()).isEqualTo(BudgetStatus.ACTIVE);
        assertThat(afterReopen.lines()).singleElement()
                .satisfies(l -> assertThat(l.limitAmount()).isEqualByComparingTo("500.00"));
    }
}
