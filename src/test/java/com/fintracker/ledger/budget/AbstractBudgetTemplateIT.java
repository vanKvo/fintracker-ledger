package com.fintracker.ledger.budget;

import org.springframework.beans.factory.annotation.Autowired;

import com.fintracker.ledger.budget.service.BudgetTemplateService;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shared fixtures for the REQ-5.3 suite.
 *
 * <p>The fixture helpers below write directly to {@code ledger.budget_templates} and
 * {@code ledger.budget_template_lines} (REQ-5.3 F.1 / F.2). Until the migration that creates those
 * tables exists they throw {@code relation "ledger.budget_templates" does not exist} — which is
 * the point, exactly as {@code AbstractBudgetIT.readStatusColumn} was written against a column the
 * REQ-5.1 migration had yet to add. The schema is part of the requirement, not an assumption of
 * the test harness.
 */
public abstract class AbstractBudgetTemplateIT extends AbstractBudgetIT {

    @Autowired
    protected BudgetTemplateService budgetTemplateService;

    /** A global template visible to every user — REQ-5.3 {@code is_system = true}, user_id NULL. */
    protected UUID insertSystemTemplate(String name) {
        var templateId = UUID.randomUUID();
        executeAsSuperuser("""
                INSERT INTO ledger.budget_templates (id, user_id, name, description, is_system)
                VALUES (?, NULL, ?, 'System template fixture', true)
                """, templateId, name);
        return templateId;
    }

    /** A template private to {@code owner} — REQ-5.3 {@code is_system = false}. */
    protected UUID insertCustomTemplate(UUID owner, String name) {
        var templateId = UUID.randomUUID();
        executeAsSuperuser("""
                INSERT INTO ledger.budget_templates (id, user_id, name, description, is_system)
                VALUES (?, ?, ?, 'Custom template fixture', false)
                """, templateId, owner, name);
        return templateId;
    }

    protected UUID insertTemplateLine(UUID templateId, String categoryName, String defaultLimit) {
        var lineId = UUID.randomUUID();
        executeAsSuperuser("""
                INSERT INTO ledger.budget_template_lines (id, template_id, category_name, default_limit)
                VALUES (?, ?, ?, ?)
                """, lineId, templateId, categoryName, new BigDecimal(defaultLimit));
        return lineId;
    }

    /** Reads template lines with a superuser connection, bypassing the read path under test. */
    protected int countTemplateLines(UUID templateId) {
        var rows = queryAsSuperuser(
                "SELECT COUNT(*) AS c FROM ledger.budget_template_lines WHERE template_id = ?", templateId);
        return ((Number) rows.get(0).get("c")).intValue();
    }

    protected String readTemplateLineLimit(UUID templateId, String categoryName) {
        var rows = queryAsSuperuser("""
                SELECT default_limit FROM ledger.budget_template_lines
                 WHERE template_id = ? AND lower(category_name) = lower(?)
                """, templateId, categoryName);
        return rows.isEmpty() ? null : rows.get(0).get("default_limit").toString();
    }
}
