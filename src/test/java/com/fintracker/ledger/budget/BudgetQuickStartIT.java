package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.dto.QuickStartBudgetRequest;
import com.fintracker.ledger.budget.dto.QuickStartBudgetRequest.CustomOverride;
import com.fintracker.ledger.budget.exception.HistoricalBudgetException;
import com.fintracker.ledger.budget.exception.LineItemLimitExceededException;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.model.BudgetStatus;
import com.fintracker.ledger.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-5.3 quick start — instantiating a budget from a template.
 *
 * <p>The central claim is <b>Copy-on-Instantiate</b>: applying a template is a one-way seed, not a
 * link. The budget's line items are independent {@code ledger.budget_lines} rows from the moment
 * they are written, in both directions — editing the budget must not touch the template, and
 * editing the template must not reach into budgets already created from it. An implementation that
 * stores a template reference instead of copying passes the shallow assertions and fails the
 * isolation tests.
 */
@AutoConfigureMockMvc
class BudgetQuickStartIT extends AbstractBudgetTemplateIT {

    private static final String QUICK_START = "/api/v1/ledger/budgets/quick-start";
    private static final String IDENTITY_HEADER = "X-Internal-User-Id";

    @Autowired
    private MockMvc mockMvc;

    private QuickStartBudgetRequest request(LocalDate month, UUID templateId,
                                            CustomOverride... overrides) {
        return new QuickStartBudgetRequest(month, templateId, null, List.of(overrides));
    }

    private static CustomOverride override(String categoryName, String limitAmount) {
        return new CustomOverride(categoryName, new BigDecimal(limitAmount));
    }

    /** A system template with Rent 1500 / Groceries 500. */
    private UUID basicLivingTemplate() {
        var templateId = insertSystemTemplate("Basic Living " + UUID.randomUUID());
        insertTemplateLine(templateId, "Rent", "1500.00");
        insertTemplateLine(templateId, "Groceries", "500.00");
        return templateId;
    }

    // ── REQ-5.3 A "Template Line Item Pre-Population" ────────────────────────────

    @Test
    @DisplayName("a budget instantiated from a template carries the template's categories and limits")
    void instantiatedBudgetCarriesTemplateLines() {
        var templateId = basicLivingTemplate();

        var budget = budgetTemplateService.instantiateQuickStartBudget(
                userId, request(currentMonth(), templateId));

        assertThat(budget.lines()).extracting(BudgetLine::category)
                .containsExactlyInAnyOrder("Rent", "Groceries");
        assertThat(budget.lines())
                .filteredOn(l -> l.category().equals("Rent"))
                .singleElement()
                .satisfies(l -> assertThat(l.limitAmount()).isEqualByComparingTo("1500.00"));
    }

    // REQ-5.1 "State Initialization" applies to a templated budget like any other.
    @Test
    @DisplayName("an instantiated budget is ACTIVE and normalized to the first of the month")
    void instantiatedBudgetIsActiveAndNormalized() {
        var templateId = basicLivingTemplate();

        var budget = budgetTemplateService.instantiateQuickStartBudget(
                userId, request(currentMonth().withDayOfMonth(17), templateId));

        assertThat(budget.status()).isEqualTo(BudgetStatus.ACTIVE);
        assertThat(budget.effectiveMonth()).isEqualTo(currentMonth());
    }

    // ── REQ-5.3 A "Template Isolation & Copy-on-Instantiate" ─────────────────────

    // "actions on instantiated budgets never mutate the source template."
    @Test
    @DisplayName("editing an instantiated budget's line does not change the template")
    void editingTheBudgetDoesNotMutateTheTemplate() {
        var templateId = basicLivingTemplate();
        var budget = budgetTemplateService.instantiateQuickStartBudget(
                userId, request(currentMonth(), templateId));
        var rentLine = budget.lines().stream()
                .filter(l -> l.category().equals("Rent")).findFirst().orElseThrow();

        budgetLineService.updateLineItemLimit(userId, budget.budgetId(), rentLine.lineId(),
                new BigDecimal("1750.00"));

        assertThat(readTemplateLineLimit(templateId, "Rent"))
                .as("the template is a source, not a live link")
                .isEqualTo("1500.00");
    }

    @Test
    @DisplayName("removing a line from an instantiated budget does not remove it from the template")
    void removingABudgetLineDoesNotTouchTheTemplate() {
        var templateId = basicLivingTemplate();
        var budget = budgetTemplateService.instantiateQuickStartBudget(
                userId, request(currentMonth(), templateId));
        var line = budget.lines().getFirst();

        budgetLineService.removeLineItem(userId, budget.budgetId(), line.lineId());

        assertThat(countTemplateLines(templateId)).isEqualTo(2);
    }

    // The other direction of isolation, and the one a reference-based implementation fails.
    @Test
    @DisplayName("a budget created from a template is unaffected by later template changes")
    void laterTemplateChangesDoNotReachExistingBudgets() {
        var templateId = basicLivingTemplate();
        var budget = budgetTemplateService.instantiateQuickStartBudget(
                userId, request(currentMonth(), templateId));

        insertTemplateLine(templateId, "Travel", "800.00");

        assertThat(budgetService.getBudgetForMonth(userId, currentMonth()).lines())
                .extracting(BudgetLine::category)
                .doesNotContain("Travel")
                .containsExactlyInAnyOrder("Rent", "Groceries");
        assertThat(budget.lines()).hasSize(2);
    }

    @Test
    @DisplayName("two users instantiating the same system template get independent budgets")
    void twoUsersInstantiatingOneTemplateGetIndependentBudgets() {
        var templateId = basicLivingTemplate();
        var otherUserId = UUID.randomUUID();

        var mine = budgetTemplateService.instantiateQuickStartBudget(
                userId, request(currentMonth(), templateId));
        actAs(otherUserId);
        var theirs = budgetTemplateService.instantiateQuickStartBudget(
                otherUserId, request(currentMonth(), templateId));

        assertThat(mine.budgetId()).isNotEqualTo(theirs.budgetId());
        assertThat(mine.lines()).extracting(BudgetLine::lineId)
                .doesNotContainAnyElementsOf(theirs.lines().stream().map(BudgetLine::lineId).toList());
    }

    // ── REQ-5.3 A "Template Line Item Overrides" ─────────────────────────────────

    // "Users may pass custom line items or explicit overrides ... to append or adjust baseline
    // template values prior to persistence."
    @Test
    @DisplayName("an override for a new category is appended to the template's lines")
    void overrideForANewCategoryIsAppended() {
        var templateId = basicLivingTemplate();

        var budget = budgetTemplateService.instantiateQuickStartBudget(userId,
                request(currentMonth(), templateId, override("Subscriptions", "50.00")));

        assertThat(budget.lines()).extracting(BudgetLine::category)
                .containsExactlyInAnyOrder("Rent", "Groceries", "Subscriptions");
    }

    @Test
    @DisplayName("an override for a template category adjusts its limit rather than duplicating it")
    void overrideForAnExistingCategoryAdjustsIt() {
        var templateId = basicLivingTemplate();

        var budget = budgetTemplateService.instantiateQuickStartBudget(userId,
                request(currentMonth(), templateId, override("Rent", "1750.00")));

        assertThat(budget.lines()).extracting(BudgetLine::category)
                .containsExactlyInAnyOrder("Rent", "Groceries");
        assertThat(budget.lines())
                .filteredOn(l -> l.category().equals("Rent"))
                .singleElement()
                .satisfies(l -> assertThat(l.limitAmount()).isEqualByComparingTo("1750.00"));
    }

    // REQ-5.2 "Category Uniqueness" is case-insensitive, so an override must match a template
    // category regardless of casing — otherwise the merge produces "Rent" and "rent" side by side
    // and the budget violates its own uniqueness rule.
    @Test
    @DisplayName("an override matches a template category case-insensitively")
    void overrideMatchesTemplateCategoryCaseInsensitively() {
        var templateId = basicLivingTemplate();

        var budget = budgetTemplateService.instantiateQuickStartBudget(userId,
                request(currentMonth(), templateId, override("rent", "1750.00")));

        assertThat(budget.lines()).hasSize(2);
        assertThat(budget.lines())
                .filteredOn(l -> l.category().equalsIgnoreCase("rent"))
                .singleElement()
                .satisfies(l -> assertThat(l.limitAmount()).isEqualByComparingTo("1750.00"));
    }

    // ── REQ-5.3 A "Previous Month Rollover" ──────────────────────────────────────

    // E: "If request.getTemplateId() is null, lines are cloned from the user's most recent
    // active budget."
    @Test
    @DisplayName("with no templateId the lines are cloned from the most recent active budget")
    void noTemplateClonesTheMostRecentActiveBudget() {
        budgetService.upsertBudget(userId, currentMonth().minusMonths(1), null,
                List.of(line("Groceries", "400.00"), line("Fuel", "120.00")));

        var budget = budgetTemplateService.instantiateQuickStartBudget(
                userId, request(currentMonth(), null));

        assertThat(budget.lines()).extracting(BudgetLine::category)
                .containsExactlyInAnyOrder("Groceries", "Fuel");
    }

    @Test
    @DisplayName("with no templateId and no prior budget an empty ACTIVE budget is created")
    void noTemplateAndNoPriorBudgetCreatesAnEmptyBudget() {
        var budget = budgetTemplateService.instantiateQuickStartBudget(
                userId, request(currentMonth(), null));

        assertThat(budget.status()).isEqualTo(BudgetStatus.ACTIVE);
        assertThat(budget.lines()).isEmpty();
    }

    // ── REQ-5.3 A "Template Line Item Pre-Population" — spend ────────────────────

    // "for current or past periods, the spentAmount for each inherited template line item shall
    // automatically query and sum approved transactions matching that category".
    @Test
    @DisplayName("inherited lines are enriched with spend for a current period")
    void inheritedLinesCarrySpendForCurrentPeriod() {
        var accountId = insertAccount(userId);
        insertPostedPurchase(accountId, "Groceries", "125.00", currentMonth().plusDays(4));
        var templateId = basicLivingTemplate();

        var budget = budgetTemplateService.instantiateQuickStartBudget(
                userId, request(currentMonth(), templateId));

        assertThat(budget.lines())
                .filteredOn(l -> l.category().equals("Groceries"))
                .singleElement()
                .satisfies(l -> assertThat(l.spentAmount()).isEqualByComparingTo("125.00"));
    }

    // "For future periods, spentAmount initializes to $0.00."
    @Test
    @DisplayName("inherited lines report 0.00 spent for a future period")
    void inheritedLinesReportZeroSpendForFuturePeriod() {
        var templateId = basicLivingTemplate();

        var budget = budgetTemplateService.instantiateQuickStartBudget(
                userId, request(futureMonth(), templateId));

        assertThat(budget.lines()).allSatisfy(l ->
                assertThat(l.spentAmount()).isEqualByComparingTo("0.00"));
    }

    // ── REQ-5.3 B Constraints ────────────────────────────────────────────────────

    // "Target Line Ceiling: Merging template items with explicit user override items must not
    // cause total lines on the created budget to exceed 50."
    @Test
    @DisplayName("template lines plus overrides exceeding 50 is rejected")
    void mergedLinesOverTheCeilingAreRejected() {
        var templateId = insertCustomTemplate(userId, "Fifty");
        for (int i = 0; i < 50; i++) {
            insertTemplateLine(templateId, "Category-" + i, "10.00");
        }

        assertThatThrownBy(() -> budgetTemplateService.instantiateQuickStartBudget(userId,
                request(currentMonth(), templateId, override("One-Too-Many", "5.00"))))
                .isInstanceOf(LineItemLimitExceededException.class);
    }

    // The accepting side of the same boundary. Without it, an implementation that rejects at
    // exactly 50 — `>=` where `>` was meant — passes every other test in this class: a mutation
    // check confirmed the off-by-one was caught by 0 of 34 tests before this was added.
    @Test
    @DisplayName("template lines plus overrides totalling exactly 50 is accepted")
    void mergedLinesAtTheCeilingAreAccepted() {
        var templateId = insertCustomTemplate(userId, "FortyNine");
        for (int i = 0; i < 49; i++) {
            insertTemplateLine(templateId, "Category-" + i, "10.00");
        }

        var budget = budgetTemplateService.instantiateQuickStartBudget(userId,
                request(currentMonth(), templateId, override("Fiftieth", "5.00")));

        assertThat(budget.lines()).hasSize(50);
    }

    // An override that adjusts an existing template line does not grow the merge, so a template
    // already at the ceiling stays instantiable. Distinguishes "count the merged result" from
    // "count template lines + override count", which agree everywhere except here.
    @Test
    @DisplayName("an override adjusting an existing category does not push a 50-line template over the ceiling")
    void adjustingOverrideDoesNotBreachTheCeiling() {
        var templateId = insertCustomTemplate(userId, "FiftyAdjust");
        for (int i = 0; i < 50; i++) {
            insertTemplateLine(templateId, "Category-" + i, "10.00");
        }

        var budget = budgetTemplateService.instantiateQuickStartBudget(userId,
                request(currentMonth(), templateId, override("Category-0", "99.00")));

        assertThat(budget.lines()).hasSize(50);
        assertThat(budget.lines())
                .filteredOn(l -> l.category().equals("Category-0"))
                .singleElement()
                .satisfies(l -> assertThat(l.limitAmount()).isEqualByComparingTo("99.00"));
    }

    @Test
    @DisplayName("a rejected instantiation persists no budget")
    void rejectedInstantiationPersistsNothing() {
        var templateId = insertCustomTemplate(userId, "Fifty");
        for (int i = 0; i < 50; i++) {
            insertTemplateLine(templateId, "Category-" + i, "10.00");
        }
        int before = countBudgetRows(userId);

        assertThatThrownBy(() -> budgetTemplateService.instantiateQuickStartBudget(userId,
                request(currentMonth(), templateId, override("One-Too-Many", "5.00"))))
                .isInstanceOf(LineItemLimitExceededException.class);

        assertThat(countBudgetRows(userId))
                .as("a rejected merge must leave no partial budget behind")
                .isEqualTo(before);
    }

    // REQ-5.3 D Error Mapping: "404 NOT FOUND — Selected templateId does not exist".
    @Test
    @DisplayName("instantiating from an unknown template is a 404-class failure")
    void unknownTemplateIsNotFound() {
        assertThatThrownBy(() -> budgetTemplateService.instantiateQuickStartBudget(
                userId, request(currentMonth(), UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("instantiating from another user's custom template is a 404-class failure")
    void anotherUsersTemplateIsNotFound() {
        var otherUserId = UUID.randomUUID();
        var theirTemplate = insertCustomTemplate(otherUserId, "Their Plan");

        assertThatThrownBy(() -> budgetTemplateService.instantiateQuickStartBudget(
                userId, request(currentMonth(), theirTemplate)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // REQ-5.3 D: "422 UNPROCESSABLE ENTITY — Attempting to instantiate into a closed budget
    // window", consistent with REQ-5.1 "Immutability upon Closure".
    @Test
    @DisplayName("instantiating into a month whose budget is CLOSED is rejected")
    void instantiatingIntoAClosedMonthIsRejected() {
        var existing = budgetService.upsertBudget(userId, currentMonth(), null,
                List.of(line("Groceries", "100.00")));
        budgetService.closeBudget(userId, existing.budgetId());
        var templateId = basicLivingTemplate();

        assertThatThrownBy(() -> budgetTemplateService.instantiateQuickStartBudget(
                userId, request(currentMonth(), templateId)))
                .isInstanceOf(HistoricalBudgetException.class);
    }

    // ── REQ-5.3 D. REST API Mapping ──────────────────────────────────────────────

    // D Success Responses: "201 CREATED — Successfully instantiated budget from template."
    @Test
    @DisplayName("POST /budgets/quick-start responds 201 Created with the instantiated budget")
    void quickStartResponds201() throws Exception {
        var templateId = basicLivingTemplate();

        mockMvc.perform(post(QUICK_START)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"effectiveMonth": "%s", "templateId": "%s", "customOverrides": []}
                                """.formatted(currentMonth(), templateId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.lines.length()").value(2));
    }

    @Test
    @DisplayName("POST /budgets/quick-start with an unknown template responds 404")
    void quickStartWithUnknownTemplateResponds404() throws Exception {
        mockMvc.perform(post(QUICK_START)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"effectiveMonth": "%s", "templateId": "%s", "customOverrides": []}
                                """.formatted(currentMonth(), UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    // D Error Mapping: "400 BAD REQUEST — Invalid monetary precision ... (InvalidBudgetException)".
    @Test
    @DisplayName("POST /budgets/quick-start with a 3-decimal override responds 400")
    void quickStartWithInvalidScaleResponds400() throws Exception {
        var templateId = basicLivingTemplate();

        mockMvc.perform(post(QUICK_START)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"effectiveMonth": "%s", "templateId": "%s",
                                 "customOverrides": [{"categoryName": "Subscriptions", "limitAmount": 10.005}]}
                                """.formatted(currentMonth(), templateId)))
                .andExpect(status().isBadRequest());
    }
}
