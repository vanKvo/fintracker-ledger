package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import com.fintracker.ledger.budget.model.Budget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-5.1 A.2 "Get Budgets" — listing every budget a user holds within one calendar year.
 *
 * <p>The claim that most needs pinning here is that this listing is a <b>pure read</b>. The
 * single-month read path ({@code getBudgetForMonth}) deliberately materializes a budget when the
 * month has none, and the obvious way to build a year listing — loop over twelve months calling
 * that method — would silently create up to twelve budget rows every time a user opened the page.
 * Several tests below exist only to make that implementation impossible to ship.
 *
 * <p>The date fixtures are pinned to the injected {@link com.fintracker.ledger.testsupport.MutableTestClock}
 * rather than to real wall-clock years, so the year-boundary assertions do not change meaning on
 * 1 January.
 */
@AutoConfigureMockMvc
class BudgetYearListingIT extends AbstractBudgetIT {

    private static final String BUDGETS = "/api/v1/ledger/budgets";
    private static final String IDENTITY_HEADER = "X-Internal-User-Id";

    @Autowired
    private MockMvc mockMvc;

    /** The 1st of {@code month} in {@code year}, as a normalized effectiveMonth. */
    private static LocalDate monthOf(int year, int month) {
        return LocalDate.of(year, month, 1);
    }

    private int currentYear() {
        return currentMonth().getYear();
    }

    // ── A.2 "Budgets in a year" ──────────────────────────────────────────────────

    // A.2 Budgets in a year: "The system get all budgets created within a year for the user."
    @Test
    @DisplayName("every budget the user holds in the requested year is returned")
    void returnsEveryBudgetOfTheRequestedYear() {
        int year = currentYear();
        budgetService.upsertBudget(userId, monthOf(year, 1), null, List.of(line("Groceries", "100.00")));
        budgetService.upsertBudget(userId, monthOf(year, 6), null, List.of(line("Groceries", "200.00")));
        budgetService.upsertBudget(userId, monthOf(year, 12), null, List.of(line("Groceries", "300.00")));

        var budgets = budgetService.getBudgetsForYear(userId, year);

        assertThat(budgets).extracting(Budget::effectiveMonth)
                .containsExactlyInAnyOrder(monthOf(year, 1), monthOf(year, 6), monthOf(year, 12));
    }

    // A.2 Ordering: "descending effectiveMonth order (most recent month first)" — the caller
    // renders a reverse-chronological period list without re-sorting.
    @Test
    @DisplayName("budgets are ordered most recent month first")
    void ordersBudgetsByMonthDescending() {
        int year = currentYear();
        budgetService.upsertBudget(userId, monthOf(year, 3), null, List.of(line("A", "1.00")));
        budgetService.upsertBudget(userId, monthOf(year, 11), null, List.of(line("A", "1.00")));
        budgetService.upsertBudget(userId, monthOf(year, 7), null, List.of(line("A", "1.00")));

        var budgets = budgetService.getBudgetsForYear(userId, year);

        assertThat(budgets).extracting(Budget::effectiveMonth)
                .containsExactly(monthOf(year, 11), monthOf(year, 7), monthOf(year, 3));
    }

    // A.2 Year Boundary: "[YYYY-01-01, YYYY-12-01] inclusive. December of the preceding year and
    // January of the following year are excluded." The inclusive edges are the whole point — an
    // implementation using an exclusive upper bound of YYYY-12-01 silently drops December.
    @Test
    @DisplayName("the year window includes January and December and excludes the adjacent months")
    void yearWindowIsInclusiveOfBothEdgesAndExcludesNeighbours() {
        int year = currentYear();
        budgetService.upsertBudget(userId, monthOf(year - 1, 12), null, List.of(line("A", "1.00")));
        budgetService.upsertBudget(userId, monthOf(year, 1), null, List.of(line("A", "1.00")));
        budgetService.upsertBudget(userId, monthOf(year, 12), null, List.of(line("A", "1.00")));
        budgetService.upsertBudget(userId, monthOf(year + 1, 1), null, List.of(line("A", "1.00")));

        var budgets = budgetService.getBudgetsForYear(userId, year);

        assertThat(budgets).extracting(Budget::effectiveMonth)
                .containsExactly(monthOf(year, 12), monthOf(year, 1));
    }

    // A.2 Pure Read: "a year with no budgets returns an empty list, not twelve newly created ones."
    @Test
    @DisplayName("a year the user has no budgets in returns an empty list, never null")
    void emptyYearReturnsEmptyList() {
        var budgets = budgetService.getBudgetsForYear(userId, currentYear() - 5);

        assertThat(budgets).isNotNull().isEmpty();
    }

    // A.2 Pure Read — the load-bearing test of this class. A year listing built by looping
    // getBudgetForMonth over twelve months passes every assertion above and fails here.
    @Test
    @DisplayName("listing a year creates no budgets")
    void listingAYearIsFreeOfSideEffects() {
        int year = currentYear();
        budgetService.upsertBudget(userId, monthOf(year, 4), null, List.of(line("A", "1.00")));
        int before = countBudgetRows(userId);

        budgetService.getBudgetsForYear(userId, year);
        budgetService.getBudgetsForYear(userId, year - 3);

        assertThat(countBudgetRows(userId))
                .as("listing must never materialize a budget for a month that has none")
                .isEqualTo(before);
    }

    // A.2 Tenant Scoping: "another user's budget for the same month is never visible."
    @Test
    @DisplayName("another user's budgets in the same year are not returned")
    void listingIsScopedToTheRequestingUser() {
        int year = currentYear();
        var otherUserId = UUID.randomUUID();
        actAs(otherUserId);
        budgetService.upsertBudget(otherUserId, monthOf(year, 5), null, List.of(line("Theirs", "999.00")));

        actAs(userId);
        budgetService.upsertBudget(userId, monthOf(year, 5), null, List.of(line("Mine", "100.00")));

        var budgets = budgetService.getBudgetsForYear(userId, year);

        assertThat(budgets).singleElement()
                .satisfies(b -> assertThat(b.lines()).extracting(l -> l.category()).containsExactly("Mine"));
    }

    // A.2 Spend Enrichment: past and current periods sum approved transactions, exactly as
    // REQ-5.1 "Spend Amount Initialization" requires of the single-month read.
    @Test
    @DisplayName("listed budgets carry per-line spentAmount for elapsed periods")
    void listedBudgetsAreEnrichedWithSpending() {
        var month = currentMonth().minusMonths(1);
        var accountId = insertAccount(userId);
        insertPostedExpense(accountId, "Groceries", "75.00", month.plusDays(10));
        budgetService.upsertBudget(userId, month, null, List.of(line("Groceries", "500.00")));

        var budgets = budgetService.getBudgetsForYear(userId, month.getYear());

        assertThat(budgets)
                .filteredOn(b -> b.effectiveMonth().equals(month))
                .singleElement()
                .satisfies(b -> assertThat(b.lines()).singleElement()
                        .satisfies(l -> assertThat(l.spentAmount()).isEqualByComparingTo("75.00")));
    }

    // A.2 Spend Enrichment — the future half of the same rule: "future periods report $0.00".
    @Test
    @DisplayName("a listed future-period budget reports 0.00 spent")
    void listedFuturePeriodBudgetsReportZeroSpend() {
        var month = futureMonth();
        budgetService.upsertBudget(userId, month, null, List.of(line("Travel", "1200.00")));

        var budgets = budgetService.getBudgetsForYear(userId, month.getYear());

        assertThat(budgets)
                .filteredOn(b -> b.effectiveMonth().equals(month))
                .singleElement()
                .satisfies(b -> assertThat(b.lines()).singleElement()
                        .satisfies(l -> assertThat(l.spentAmount()).isEqualByComparingTo("0.00")));
    }

    // A.2 Get Budgets — the listing reports each budget's real status, which is what lets the
    // client label elapsed periods CLOSED without opening each month in turn.
    @Test
    @DisplayName("the listing reports each budget's own status")
    void listingReportsEachBudgetsStatus() {
        int year = currentYear();
        var closed = budgetService.upsertBudget(userId, monthOf(year, 2), null, List.of(line("A", "1.00")));
        budgetService.closeBudget(userId, closed.budgetId());
        budgetService.upsertBudget(userId, monthOf(year, 3), null, List.of(line("A", "1.00")));

        var budgets = budgetService.getBudgetsForYear(userId, year);

        assertThat(budgets)
                .filteredOn(b -> b.budgetId().equals(closed.budgetId()))
                .singleElement()
                .satisfies(b -> assertThat(b.status().name()).isEqualTo("CLOSED"));
    }

    // E. Interface Details: "@throws InvalidBudgetException if userId is null or year is outside
    // [1970, 9999]" — a bad year is a client error, never an empty result that looks like success.
    @Test
    @DisplayName("a year outside [1970, 9999] is rejected as a validation failure")
    void outOfRangeYearIsRejected() {
        assertThatThrownBy(() -> budgetService.getBudgetsForYear(userId, 1969))
                .isInstanceOf(InvalidBudgetException.class);
        assertThatThrownBy(() -> budgetService.getBudgetsForYear(userId, 10000))
                .isInstanceOf(InvalidBudgetException.class);
    }

    @Test
    @DisplayName("a null userId is rejected as a validation failure")
    void nullUserIdIsRejected() {
        assertThatThrownBy(() -> budgetService.getBudgetsForYear(null, currentYear()))
                .isInstanceOf(InvalidBudgetException.class);
    }

    // ── Year navigation: which years are worth asking about ──────────────────────

    // An empty result is the authoritative "this user has never created a budget" signal the
    // Budgets page needs — distinct from "the current year happens to be empty".
    @Test
    @DisplayName("a user with no budgets has no budget years")
    void userWithNoBudgetsHasNoBudgetYears() {
        assertThat(budgetService.getBudgetYears(userId)).isNotNull().isEmpty();
    }

    // Distinct and descending: the first element is the most recent year, the last is the year the
    // user started budgeting.
    @Test
    @DisplayName("budget years are distinct and ordered most recent first")
    void budgetYearsAreDistinctAndDescending() {
        int year = currentYear();
        budgetService.upsertBudget(userId, monthOf(year, 3), null, List.of(line("A", "1.00")));
        budgetService.upsertBudget(userId, monthOf(year, 9), null, List.of(line("A", "1.00")));
        budgetService.upsertBudget(userId, monthOf(year - 2, 5), null, List.of(line("A", "1.00")));

        assertThat(budgetService.getBudgetYears(userId)).containsExactly(year, year - 2);
    }

    @Test
    @DisplayName("another user's budget years are not reported")
    void budgetYearsAreScopedToTheRequestingUser() {
        var otherUserId = UUID.randomUUID();
        actAs(otherUserId);
        budgetService.upsertBudget(otherUserId, monthOf(currentYear() - 4, 1), null, List.of(line("A", "1.00")));

        actAs(userId);
        budgetService.upsertBudget(userId, monthOf(currentYear(), 1), null, List.of(line("A", "1.00")));

        assertThat(budgetService.getBudgetYears(userId)).containsExactly(currentYear());
    }

    @Test
    @DisplayName("GET /years responds 200 OK with the user's budget years, most recent first")
    void getBudgetYearsResponds200() throws Exception {
        int year = currentYear();
        budgetService.upsertBudget(userId, monthOf(year, 6), null, List.of(line("A", "1.00")));
        budgetService.upsertBudget(userId, monthOf(year - 1, 6), null, List.of(line("A", "1.00")));

        mockMvc.perform(get(BUDGETS + "/years").header(IDENTITY_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(year))
                .andExpect(jsonPath("$[1]").value(year - 1));
    }

    @Test
    @DisplayName("GET /years for a user with no budgets responds 200 OK with an empty array")
    void getBudgetYearsForNewUserRespondsEmptyArray() throws Exception {
        mockMvc.perform(get(BUDGETS + "/years").header(IDENTITY_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── D. REST API Mapping — GET /api/v1/budgets?year={YYYY} ────────────────────

    // D. Endpoints: "GET /api/v1/budgets?year={YYYY} — List every existing budget of the user in
    // that calendar year." The ?month= form of the same URL must keep working alongside it.
    @Test
    @DisplayName("GET ?year= responds 200 OK with the year's budgets as a JSON array")
    void getByYearResponds200WithAnArray() throws Exception {
        int year = currentYear();
        budgetService.upsertBudget(userId, monthOf(year, 8), null, List.of(line("Groceries", "500.00")));

        mockMvc.perform(get(BUDGETS).param("year", String.valueOf(year))
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].effectiveMonth").value(monthOf(year, 8).toString()));
    }

    // D. Success Responses: "A year listing with no budgets is 200 OK with an empty array,
    // never 404."
    @Test
    @DisplayName("GET ?year= for a year with no budgets responds 200 OK with an empty array")
    void getByEmptyYearResponds200WithEmptyArray() throws Exception {
        mockMvc.perform(get(BUDGETS).param("year", String.valueOf(currentYear() - 5))
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // D. Endpoints — ?month= and ?year= share one URL, so routing between them must be driven by
    // which parameter is present. This pins that adding ?year= did not break the single-month read.
    @Test
    @DisplayName("GET ?month= still responds with a single budget object, not an array")
    void getByMonthStillReturnsASingleObject() throws Exception {
        mockMvc.perform(get(BUDGETS).param("month", currentMonth().toString())
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveMonth").value(currentMonth().toString()));
    }

    // D. Error Mappings: "400 BAD REQUEST — ... Includes a year outside [1970, 9999] and a
    // non-numeric year on the listing endpoint."
    @Test
    @DisplayName("GET with a non-numeric year responds 400 Bad Request")
    void getByNonNumericYearResponds400() throws Exception {
        mockMvc.perform(get(BUDGETS).param("year", "not-a-year")
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET with an out-of-range year responds 400 Bad Request")
    void getByOutOfRangeYearResponds400() throws Exception {
        mockMvc.perform(get(BUDGETS).param("year", "1969")
                        .header(IDENTITY_HEADER, userId))
                .andExpect(status().isBadRequest());
    }

    // ── Framework-level client errors must not read as server faults ─────────────
    //
    // These three used to fall through to the Exception catch-all and return 500. That made a
    // client calling a route the running build does not have (a stale deploy, an old cached
    // bundle) indistinguishable from a genuine outage — the UI reported "an unexpected error
    // occurred" and the 5xx rate spiked, for what was in every case a client-side mistake.

    @Test
    @DisplayName("a path with no mapping at all responds 404, not 500")
    void unmappedPathResponds404() throws Exception {
        mockMvc.perform(get(BUDGETS + "/no/such/endpoint").header(IDENTITY_HEADER, userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("an unsupported method on a mapped path responds 405, not 500")
    void unsupportedMethodResponds405() throws Exception {
        mockMvc.perform(patch(BUDGETS).header(IDENTITY_HEADER, userId))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("GET with neither month nor year responds 400, not 500")
    void getWithNoParameterResponds400() throws Exception {
        mockMvc.perform(get(BUDGETS).header(IDENTITY_HEADER, userId))
                .andExpect(status().isBadRequest());
    }
}
