package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.model.BudgetLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-5.1 invariants — the properties that must hold across the whole legal input space rather
 * than at a particular value.
 *
 * <p>These exist to defeat an implementation tuned to the other tests' literals. The generated
 * case walks a seeded pseudo-random sample of legal payloads and asserts round-trip identity, so a
 * lookup table of the categories and amounts used elsewhere in this suite does not survive. The
 * seed is fixed so a failure is reproducible.
 */
class BudgetInvariantsIT extends AbstractBudgetIT {

    // REQ-5.1 Period Selection & Upsert Behavior: "submitting a valid payload will update the
    // budget's line items rather than throwing a duplicate error." Applied twice, the same payload
    // must therefore converge on the same state rather than accumulating or failing.
    @Test
    @DisplayName("applying the same payload twice is idempotent")
    void repeatedIdenticalUpsertIsIdempotent() {
        var month = currentMonth();
        var payload = List.of(line("Groceries", "500.00"), line("Dining", "200.00"));

        var first = budgetService.upsertBudget(userId, month, null, payload);
        var second = budgetService.upsertBudget(userId, month, null, payload);

        assertThat(second.budgetId()).isEqualTo(first.budgetId());
        assertThat(countBudgetRows(userId)).isEqualTo(1);
        assertThat(countLineRows(first.budgetId())).isEqualTo(2);
    }

    // A service must not mutate its caller's arguments — the caller still owns that list and may
    // reuse it. Sorting or de-duplicating in place is the usual way this gets broken.
    @Test
    @DisplayName("the caller's list of lines is not mutated")
    void callerSuppliedListIsNotMutated() {
        var payload = new ArrayList<>(List.of(
                line("Groceries", "500.00"), line("Dining", "200.00"), line("Travel", "50.00")));
        var snapshot = List.copyOf(payload);

        budgetService.upsertBudget(userId, currentMonth(), null, payload);

        assertThat(payload).containsExactlyElementsOf(snapshot);
    }

    // Persisted state must be independent of what the caller does with its list afterwards — the
    // service stores values, not a live reference into the caller's collection.
    @Test
    @DisplayName("mutating the caller's list after the call does not change stored state")
    void laterCallerMutationDoesNotReachStoredState() {
        var payload = new ArrayList<>(List.of(line("Groceries", "500.00")));
        var budget = budgetService.upsertBudget(userId, currentMonth(), null, payload);

        payload.add(line("Smuggled", "1.00"));
        payload.set(0, line("Groceries", "999.00"));

        assertThat(readLineRows(budget.budgetId())).singleElement()
                .satisfies(row -> {
                    assertThat(row.get("category")).isEqualTo("Groceries");
                    assertThat(row.get("limit_amount")).hasToString("500.00");
                });
    }

    // Two identical requests must produce identically ordered responses; otherwise a client
    // rendering the lines in received order sees them shuffle for no reason.
    @Test
    @DisplayName("line ordering is stable across identical requests")
    void lineOrderingIsStable() {
        var month = currentMonth();
        var payload = List.of(line("Groceries", "500.00"), line("Dining", "200.00"), line("Travel", "50.00"));

        var first = budgetService.upsertBudget(userId, month, null, payload);
        var second = budgetService.upsertBudget(userId, month, null, payload);
        var read = budgetService.getBudgetForMonth(userId, month);

        assertThat(second.lines()).extracting(BudgetLine::category)
                .containsExactlyElementsOf(first.lines().stream().map(BudgetLine::category).toList());
        assertThat(read.lines()).extracting(BudgetLine::category)
                .containsExactlyElementsOf(first.lines().stream().map(BudgetLine::category).toList());
    }

    // REQ-5.1 Normalization Rule + upsert semantics: what was written is what comes back, through
    // the ordinary read path rather than the writer's own return value.
    @Test
    @DisplayName("a written budget round-trips through the read path unchanged")
    void writtenBudgetRoundTripsThroughTheReadPath() {
        var midMarch = java.time.LocalDate.of(2026, 3, 17);
        var written = budgetService.upsertBudget(userId, midMarch, null,
                List.of(line("Groceries", "500.00"), line("Dining", "200.00")));

        var read = budgetService.getBudgetForMonth(userId, midMarch);

        assertThat(read.budgetId()).isEqualTo(written.budgetId());
        assertThat(read.effectiveMonth()).isEqualTo(written.effectiveMonth());
        assertThat(read.status()).isEqualTo(written.status());
        assertThat(read.lines()).extracting(BudgetLine::category)
                .containsExactlyInAnyOrder("Groceries", "Dining");
    }

    // Property: across a sample of the whole legal input space — any line count from 1 to the
    // 50-item ceiling, any amount within [0.00, 999999999.99] at scale 2 — every payload is
    // accepted and every limit round-trips at its exact value. Literal-matching shortcuts that
    // satisfy the fixed-value tests elsewhere in this suite do not survive generated input.
    @Test
    @DisplayName("property: any legal payload is accepted and round-trips exactly")
    void anyLegalPayloadRoundTripsExactly() {
        var random = new Random(20260728L);

        for (int iteration = 0; iteration < 20; iteration++) {
            var owner = java.util.UUID.randomUUID();
            actAs(owner);
            var month = currentMonth().plusMonths(random.nextInt(6));

            int lineCount = 1 + random.nextInt(50);
            var payload = new ArrayList<BudgetLine>(lineCount);
            for (int i = 0; i < lineCount; i++) {
                var amount = BigDecimal.valueOf(random.nextDouble() * 999_999_999.99d)
                        .setScale(2, RoundingMode.DOWN);
                payload.add(new BudgetLine(null, null, "Cat-" + iteration + "-" + i, amount, null, null));
            }

            var budget = budgetService.upsertBudget(owner, month, null, payload);

            assertThat(budget.lines())
                    .as("iteration %d: every submitted line is persisted", iteration)
                    .hasSize(lineCount);
            assertThat(countLineRows(budget.budgetId())).isEqualTo(lineCount);

            var byCategory = budget.lines().stream()
                    .collect(java.util.stream.Collectors.toMap(BudgetLine::category, BudgetLine::limitAmount));
            for (var submitted : payload) {
                assertThat(byCategory.get(submitted.category()))
                        .as("iteration %d: limit for %s survives the round trip exactly",
                                iteration, submitted.category())
                        .isEqualByComparingTo(submitted.limitAmount());
            }
        }
    }
}
