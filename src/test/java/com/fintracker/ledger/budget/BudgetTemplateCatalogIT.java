package com.fintracker.ledger.budget;

import com.fintracker.ledger.budget.model.BudgetTemplate;
import com.fintracker.ledger.budget.model.BudgetTemplateLine;
import com.fintracker.ledger.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-5.3 template catalog — listing and inspecting templates.
 *
 * <p>The claim that needs the most protection here is the <b>two ownership models in one
 * listing</b>. System templates have {@code user_id IS NULL} and must be visible to everyone;
 * custom templates must be visible only to their owner. Every other table in this schema is
 * governed by the single RLS predicate {@code user_id = current_setting('app.current_user_id')},
 * which evaluates NULL — and therefore false — for every system template. An implementation that
 * copies that policy verbatim produces a catalog in which the system templates are invisible to
 * all users, and passes no test below.
 */
@AutoConfigureMockMvc
class BudgetTemplateCatalogIT extends AbstractBudgetTemplateIT {

    private static final String TEMPLATES = "/api/v1/ledger/budget-templates";
    private static final String IDENTITY_HEADER = "X-Internal-User-Id";

    @Autowired
    private MockMvc mockMvc;

    // REQ-5.3 A "System & Custom Template Availability": "The system provides predefined global
    // templates (is_system = true ...)". Visible to every user, owned by none.
    @Test
    @DisplayName("system templates are visible to every user")
    void systemTemplatesAreVisibleToEveryUser() {
        var templateId = insertSystemTemplate("Basic Living " + UUID.randomUUID());

        assertThat(budgetTemplateService.getAvailableTemplates(userId))
                .extracting(BudgetTemplate::templateId)
                .contains(templateId);
    }

    @Test
    @DisplayName("a system template reports isSystem true and no owner")
    void systemTemplateCarriesNoOwner() {
        var templateId = insertSystemTemplate("Aggressive Savings " + UUID.randomUUID());

        assertThat(budgetTemplateService.getTemplateById(userId, templateId))
                .satisfies(t -> {
                    assertThat(t.isSystem()).isTrue();
                    assertThat(t.userId()).isNull();
                });
    }

    // REQ-5.3 A: "... and user-owned custom templates (is_system = false)".
    @Test
    @DisplayName("a user's own custom templates appear in their catalog")
    void ownCustomTemplatesAppearInTheCatalog() {
        var templateId = insertCustomTemplate(userId, "My Plan");

        assertThat(budgetTemplateService.getAvailableTemplates(userId))
                .extracting(BudgetTemplate::templateId)
                .contains(templateId);
    }

    // The load-bearing tenancy test: a custom template belongs to exactly one user.
    @Test
    @DisplayName("another user's custom template is not in the catalog")
    void otherUsersCustomTemplatesAreNotVisible() {
        var otherUserId = UUID.randomUUID();
        var theirTemplate = insertCustomTemplate(otherUserId, "Their Plan");

        assertThat(budgetTemplateService.getAvailableTemplates(userId))
                .extracting(BudgetTemplate::templateId)
                .doesNotContain(theirTemplate);
    }

    // REQ-5.3 E getTemplateById "@throws ResourceNotFoundException If templateId does not exist or
    // user lacks access" — the two are deliberately indistinguishable, so a probe cannot confirm
    // that another user's template exists.
    @Test
    @DisplayName("fetching another user's custom template is a 404-class failure")
    void fetchingAnotherUsersTemplateIsNotFound() {
        var otherUserId = UUID.randomUUID();
        var theirTemplate = insertCustomTemplate(otherUserId, "Their Plan");

        assertThatThrownBy(() -> budgetTemplateService.getTemplateById(userId, theirTemplate))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("fetching an unknown template is a 404-class failure")
    void fetchingAnUnknownTemplateIsNotFound() {
        assertThatThrownBy(() -> budgetTemplateService.getTemplateById(userId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // REQ-5.3 E getTemplateById: "The detailed template DTO including line item definitions."
    @Test
    @DisplayName("a fetched template carries its line items with category and default limit")
    void templateCarriesItsLineItems() {
        var templateId = insertSystemTemplate("Basic Living " + UUID.randomUUID());
        insertTemplateLine(templateId, "Rent", "1500.00");
        insertTemplateLine(templateId, "Groceries", "500.00");

        var template = budgetTemplateService.getTemplateById(userId, templateId);

        assertThat(template.lines()).hasSize(2)
                .extracting(BudgetTemplateLine::categoryName)
                .containsExactlyInAnyOrder("Rent", "Groceries");
        assertThat(template.lines())
                .filteredOn(l -> l.categoryName().equals("Rent"))
                .singleElement()
                .satisfies(l -> assertThat(l.defaultLimit()).isEqualByComparingTo("1500.00"));
    }

    @Test
    @DisplayName("a template with no lines reports an empty list, never null")
    void templateWithNoLinesReportsEmptyList() {
        var templateId = insertCustomTemplate(userId, "Empty Plan");

        assertThat(budgetTemplateService.getTemplateById(userId, templateId).lines())
                .isNotNull().isEmpty();
    }

    // REQ-5.3 B "Template Limit Ceiling: A template cannot contain more than 50 line items."
    // Read back rather than asserted on the write path, since templates are seeded by fixture here.
    @Test
    @DisplayName("a template at the 50-line ceiling is returned in full")
    void templateAtTheCeilingIsReturnedInFull() {
        var templateId = insertCustomTemplate(userId, "Fifty");
        for (int i = 0; i < 50; i++) {
            insertTemplateLine(templateId, "Category-" + i, "10.00");
        }

        assertThat(budgetTemplateService.getTemplateById(userId, templateId).lines()).hasSize(50);
    }

    @Test
    @DisplayName("a user with no custom templates still sees the system catalog")
    void catalogIsNeverEmptyForAUserWithNoTemplatesOfTheirOwn() {
        var systemTemplate = insertSystemTemplate("Basic Living " + UUID.randomUUID());

        var catalog = budgetTemplateService.getAvailableTemplates(userId);

        assertThat(catalog).extracting(BudgetTemplate::templateId).contains(systemTemplate);
        assertThat(catalog).allSatisfy(t ->
                assertThat(t.isSystem() || userId.equals(t.userId())).isTrue());
    }

    // ── REQ-5.3 D. REST API Mapping ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /budget-templates responds 200 OK with the visible catalog")
    void listTemplatesResponds200() throws Exception {
        insertSystemTemplate("Basic Living " + UUID.randomUUID());

        mockMvc.perform(get(TEMPLATES).header(IDENTITY_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /budget-templates/{id} responds 200 OK with the template's lines")
    void getTemplateResponds200() throws Exception {
        var templateId = insertSystemTemplate("Basic Living " + UUID.randomUUID());
        insertTemplateLine(templateId, "Rent", "1500.00");

        mockMvc.perform(get(TEMPLATES + "/" + templateId).header(IDENTITY_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateId").value(templateId.toString()))
                .andExpect(jsonPath("$.lines[0].categoryName").value("Rent"));
    }

    // REQ-5.3 D Error Mapping: "404 NOT FOUND — Selected templateId does not exist".
    //
    // The problem type is asserted, not just the status. With no controller mounted at all this
    // path 404s anyway — as "endpoint-not-found" — so a bare status check would pass today for
    // entirely the wrong reason. "resource-not-found" can only come from the endpoint existing
    // and reporting that the template does not.
    @Test
    @DisplayName("GET /budget-templates/{id} for an unknown template responds 404 from the endpoint, not the router")
    void getUnknownTemplateResponds404() throws Exception {
        mockMvc.perform(get(TEMPLATES + "/" + UUID.randomUUID()).header(IDENTITY_HEADER, userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://api.fintracker.com/problems/resource-not-found"));
    }
}
