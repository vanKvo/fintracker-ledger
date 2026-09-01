package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.exception.DuplicateTemplateException;
import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import com.fintracker.ledger.budget.exception.LineItemLimitExceededException;
import com.fintracker.ledger.budget.model.BudgetTemplate;
import com.fintracker.ledger.budget.model.BudgetTemplateLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-5.3 A "Custom Template Creation" — the Save-as-Template write path.
 *
 * <p>Two claims carry the most weight. First, <b>the system catalog is read-only to the
 * application</b>: creation can only ever produce an owned template, so no request can manufacture
 * a globally-visible one. Second, <b>allocations are read server-side from the source budget</b>
 * rather than taken from the request body, so a template can never be stored with figures the
 * source budget does not contain — a client that lies about the amounts must not be believed.
 */
@AutoConfigureMockMvc
class BudgetTemplateCreationIT extends AbstractBudgetTemplateIT {

    private static final String TEMPLATES = "/api/v1/ledger/budget-templates";
    private static final String IDENTITY_HEADER = "X-Internal-User-Id";

    @Autowired
    private MockMvc mockMvc;

    private static BudgetTemplateLine templateLine(String categoryName, String defaultLimit) {
        return new BudgetTemplateLine(null, null, categoryName, new BigDecimal(defaultLimit));
    }

    private static List<BudgetTemplateLine> distinctTemplateLines(int count) {
        var lines = new ArrayList<BudgetTemplateLine>(count);
        for (int i = 0; i < count; i++) {
            lines.add(templateLine("Category-" + i, "10.00"));
        }
        return List.copyOf(lines);
    }

    /** A name no other test in this shared-database suite can collide with. */
    private String uniqueName() {
        return "Plan-" + UUID.randomUUID();
    }

    // ── Creation ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a saved template is owned by its creator and is not a system template")
    void savedTemplateIsOwnedAndNotSystem() {
        var created = budgetTemplateService.createCustomTemplate(
                userId, uniqueName(), "desc", List.of(templateLine("Groceries", "500.00")));

        assertThat(created.userId()).isEqualTo(userId);
        assertThat(created.isSystem())
                .as("creation must never be able to produce a globally-visible template")
                .isFalse();
        assertThat(created.lines()).singleElement()
                .satisfies(l -> {
                    assertThat(l.categoryName()).isEqualTo("Groceries");
                    assertThat(l.defaultLimit()).isEqualByComparingTo("500.00");
                });
    }

    @Test
    @DisplayName("a saved template appears in its owner's catalog and nobody else's")
    void savedTemplateIsVisibleOnlyToItsOwner() {
        var created = budgetTemplateService.createCustomTemplate(
                userId, uniqueName(), null, List.of(templateLine("Groceries", "500.00")));

        var otherUserId = UUID.randomUUID();
        actAs(otherUserId);
        assertThat(budgetTemplateService.getAvailableTemplates(otherUserId))
                .extracting(BudgetTemplate::templateId)
                .doesNotContain(created.templateId());

        actAs(userId);
        assertThat(budgetTemplateService.getAvailableTemplates(userId))
                .extracting(BudgetTemplate::templateId)
                .contains(created.templateId());
    }

    // ── B "Template Name Uniqueness" ────────────────────────────────────────────

    @Test
    @DisplayName("reusing a name is rejected with DuplicateTemplateException")
    void duplicateNameIsRejected() {
        var name = uniqueName();
        budgetTemplateService.createCustomTemplate(userId, name, null, List.of(templateLine("A", "1.00")));

        assertThatThrownBy(() -> budgetTemplateService.createCustomTemplate(
                userId, name, null, List.of(templateLine("B", "2.00"))))
                .isInstanceOf(DuplicateTemplateException.class);
    }

    // "unique per user account (case-insensitive)" — the rule is about identity, not spelling.
    @Test
    @DisplayName("a name differing only in case is a duplicate")
    void nameUniquenessIsCaseInsensitive() {
        var name = uniqueName();
        budgetTemplateService.createCustomTemplate(userId, name.toLowerCase(), null,
                List.of(templateLine("A", "1.00")));

        assertThatThrownBy(() -> budgetTemplateService.createCustomTemplate(
                userId, name.toUpperCase(), null, List.of(templateLine("B", "2.00"))))
                .isInstanceOf(DuplicateTemplateException.class);
    }

    // Surrounding whitespace is not a distinguishing feature of a name either.
    @Test
    @DisplayName("a name differing only in surrounding whitespace is a duplicate")
    void nameUniquenessIgnoresSurroundingWhitespace() {
        var name = uniqueName();
        budgetTemplateService.createCustomTemplate(userId, name, null, List.of(templateLine("A", "1.00")));

        assertThatThrownBy(() -> budgetTemplateService.createCustomTemplate(
                userId, "  " + name + "  ", null, List.of(templateLine("B", "2.00"))))
                .isInstanceOf(DuplicateTemplateException.class);
    }

    // "unique per user account" — the scope is the account, so two users may hold the same name.
    @Test
    @DisplayName("two different users may each hold a template with the same name")
    void nameUniquenessIsScopedPerUser() {
        var name = uniqueName();
        budgetTemplateService.createCustomTemplate(userId, name, null, List.of(templateLine("A", "1.00")));

        var otherUserId = UUID.randomUUID();
        actAs(otherUserId);

        assertThatCode(() -> budgetTemplateService.createCustomTemplate(
                otherUserId, name, null, List.of(templateLine("A", "1.00"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a rejected duplicate persists nothing")
    void rejectedDuplicatePersistsNothing() {
        var name = uniqueName();
        budgetTemplateService.createCustomTemplate(userId, name, null, List.of(templateLine("A", "1.00")));
        int before = budgetTemplateService.getAvailableTemplates(userId).size();

        assertThatThrownBy(() -> budgetTemplateService.createCustomTemplate(
                userId, name, null, List.of(templateLine("B", "2.00"))))
                .isInstanceOf(DuplicateTemplateException.class);

        assertThat(budgetTemplateService.getAvailableTemplates(userId)).hasSize(before);
    }

    // ── B "Template Limit Ceiling" — both sides of the boundary ─────────────────

    @Test
    @DisplayName("a template with exactly 50 lines is accepted")
    void exactlyFiftyLinesIsAccepted() {
        var created = budgetTemplateService.createCustomTemplate(
                userId, uniqueName(), null, distinctTemplateLines(50));

        assertThat(created.lines()).hasSize(50);
    }

    @Test
    @DisplayName("a template with 51 lines is rejected")
    void fiftyOneLinesIsRejected() {
        assertThatThrownBy(() -> budgetTemplateService.createCustomTemplate(
                userId, uniqueName(), null, distinctTemplateLines(51)))
                .isInstanceOf(LineItemLimitExceededException.class);
    }

    @Test
    @DisplayName("a template rejected for size persists nothing")
    void oversizedTemplatePersistsNothing() {
        var name = uniqueName();
        int before = budgetTemplateService.getAvailableTemplates(userId).size();

        assertThatThrownBy(() -> budgetTemplateService.createCustomTemplate(
                userId, name, null, distinctTemplateLines(51)))
                .isInstanceOf(LineItemLimitExceededException.class);

        assertThat(budgetTemplateService.getAvailableTemplates(userId)).hasSize(before);
    }

    // ── Payload validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a blank name is rejected as a validation failure")
    void blankNameIsRejected() {
        assertThatThrownBy(() -> budgetTemplateService.createCustomTemplate(
                userId, "   ", null, List.of(templateLine("A", "1.00"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    @Test
    @DisplayName("duplicate categories within one template are rejected case-insensitively")
    void duplicateCategoriesWithinATemplateAreRejected() {
        assertThatThrownBy(() -> budgetTemplateService.createCustomTemplate(
                userId, uniqueName(), null,
                List.of(templateLine("Groceries", "500.00"), templateLine("GROCERIES", "300.00"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    // REQ-5.1 B Monetary Precision & Scale applies to a template default exactly as to a live
    // ceiling — reject, never silently round.
    @Test
    @DisplayName("a defaultLimit with three decimal places is rejected rather than rounded")
    void threeDecimalDefaultLimitIsRejected() {
        assertThatThrownBy(() -> budgetTemplateService.createCustomTemplate(
                userId, uniqueName(), null, List.of(templateLine("Groceries", "10.005"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    @Test
    @DisplayName("a negative defaultLimit is rejected")
    void negativeDefaultLimitIsRejected() {
        assertThatThrownBy(() -> budgetTemplateService.createCustomTemplate(
                userId, uniqueName(), null, List.of(templateLine("Groceries", "-0.01"))))
                .isInstanceOf(InvalidBudgetException.class);
    }

    @Test
    @DisplayName("a defaultLimit of exactly 999999999.99 is accepted")
    void upperBoundDefaultLimitIsAccepted() {
        var created = budgetTemplateService.createCustomTemplate(
                userId, uniqueName(), null, List.of(templateLine("Groceries", "999999999.99")));

        assertThat(created.lines()).singleElement()
                .satisfies(l -> assertThat(l.defaultLimit()).isEqualByComparingTo("999999999.99"));
    }

    @Test
    @DisplayName("a template with no lines is accepted and reports an empty list")
    void emptyTemplateIsAccepted() {
        var created = budgetTemplateService.createCustomTemplate(userId, uniqueName(), null, List.of());

        assertThat(created.lines()).isNotNull().isEmpty();
    }

    // Invariant: the caller's list is an input, not storage the service may edit.
    @Test
    @DisplayName("the supplied line list is not mutated by the service")
    void suppliedLineListIsNotMutated() {
        var input = new ArrayList<>(List.of(templateLine("Groceries", "500.00")));
        var snapshot = List.copyOf(input);

        budgetTemplateService.createCustomTemplate(userId, uniqueName(), null, input);

        assertThat(input).isEqualTo(snapshot);
    }

    // ── D. REST API Mapping ─────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /budget-templates responds 201 Created with the saved template")
    void createResponds201() throws Exception {
        mockMvc.perform(post(TEMPLATES)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "description": "d",
                                 "lines": [{"categoryName": "Groceries", "defaultLimit": 500.00}]}
                                """.formatted(uniqueName())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isSystem").value(false))
                .andExpect(jsonPath("$.lines[0].categoryName").value("Groceries"));
    }

    @Test
    @DisplayName("POST /budget-templates with a duplicate name responds 409 Conflict")
    void duplicateNameResponds409() throws Exception {
        var name = uniqueName();
        budgetTemplateService.createCustomTemplate(userId, name, null, List.of(templateLine("A", "1.00")));

        mockMvc.perform(post(TEMPLATES)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "lines": [{"categoryName": "B", "defaultLimit": 2.00}]}
                                """.formatted(name)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /budget-templates with a blank name responds 400 Bad Request")
    void blankNameResponds400() throws Exception {
        mockMvc.perform(post(TEMPLATES)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "  ", "lines": [{"categoryName": "A", "defaultLimit": 1.00}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ── "Save as Template" from a source budget ─────────────────────────────────

    // The allocations must come from the budget, not the request. A client that submits a
    // sourceBudgetId gets that budget's real figures.
    @Test
    @DisplayName("saving from a source budget copies that budget's own allocations")
    void savingFromASourceBudgetReadsTheBudgetServerSide() throws Exception {
        var budget = budgetService.upsertBudget(userId, currentMonth(), null,
                List.of(line("Groceries", "500.00"), line("Fuel", "120.00")));

        mockMvc.perform(post(TEMPLATES)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "sourceBudgetId": "%s"}
                                """.formatted(uniqueName(), budget.budgetId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines.length()").value(2));
    }

    // A source budget the caller does not own is a 404 — and must certainly not have its
    // allocations copied into the caller's template.
    @Test
    @DisplayName("saving from another user's budget responds 404 and stores nothing")
    void savingFromAnotherUsersBudgetIsNotFound() throws Exception {
        var otherUserId = UUID.randomUUID();
        actAs(otherUserId);
        var theirBudget = budgetService.upsertBudget(otherUserId, currentMonth(), null,
                List.of(line("Secret", "999.00")));

        actAs(userId);
        int before = budgetTemplateService.getAvailableTemplates(userId).size();

        mockMvc.perform(post(TEMPLATES)
                        .header(IDENTITY_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "sourceBudgetId": "%s"}
                                """.formatted(uniqueName(), theirBudget.budgetId())))
                .andExpect(status().isNotFound());

        assertThat(budgetTemplateService.getAvailableTemplates(userId)).hasSize(before);
    }

    // Round trip: a budget saved as a template and instantiated into another month reproduces the
    // same allocations. Exercises create -> catalog -> quick-start as one chain.
    @Test
    @DisplayName("a budget saved as a template reproduces its allocations when instantiated")
    void savedTemplateRoundTripsThroughQuickStart() {
        var source = budgetService.upsertBudget(userId, currentMonth(), null,
                List.of(line("Groceries", "500.00"), line("Fuel", "120.00")));

        var template = budgetTemplateService.createCustomTemplate(userId, uniqueName(), null,
                source.lines().stream()
                        .map(l -> new BudgetTemplateLine(null, null, l.category(), l.limitAmount()))
                        .toList());

        var instantiated = budgetTemplateService.instantiateQuickStartBudget(userId,
                new com.fintracker.ledger.budget.dto.QuickStartBudgetRequest(
                        currentMonth().plusMonths(1), template.templateId(), null, List.of()));

        assertThat(instantiated.lines())
                .extracting(l -> l.category() + "=" + l.limitAmount().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("Groceries=500", "Fuel=120");
    }
}
