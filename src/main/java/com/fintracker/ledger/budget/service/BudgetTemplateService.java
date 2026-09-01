package com.fintracker.ledger.budget.service;

import com.fintracker.ledger.budget.dto.QuickStartBudgetRequest;
import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetTemplate;
import com.fintracker.ledger.budget.model.BudgetTemplateLine;

import java.util.List;
import java.util.UUID;

/**
 * REQ-5.3 "Quick Start Templates".
 *
 * <p><b>Deviation from the written contract, deliberate.</b> REQ-5.3 E types these methods against
 * {@code BudgetTemplateDTO} and {@code BudgetDTO}. This module returns domain records from its
 * services throughout ({@code BudgetService} returns {@link Budget}, not a DTO), and CLAUDE.md
 * makes records the DTO/value-object mechanism, so introducing a parallel {@code BudgetDTO} would
 * mean two representations of a budget crossing the same boundary. The signatures below reuse
 * {@link Budget} and {@link BudgetTemplate}.
 */
public interface BudgetTemplateService {

    /**
     * REQ-5.3 A "System &amp; Custom Template Availability": every predefined system template
     * ({@code is_system = true}) plus the requesting user's own custom templates.
     *
     * <p>System templates belong to no user, so they must be visible to every user while custom
     * templates stay strictly private to their owner — a single listing spanning both ownership
     * models.
     *
     * @return the visible catalog; empty when no templates exist at all — never null.
     *
     * @throws com.fintracker.ledger.budget.exception.InvalidBudgetException {@code userId} is null.
     */
    List<BudgetTemplate> getAvailableTemplates(UUID userId);

    /**
     * REQ-5.3 E: one template with its line items.
     *
     * @throws com.fintracker.ledger.shared.exception.ResourceNotFoundException
     *         the template does not exist, or is another user's custom template — the two cases
     *         are deliberately indistinguishable.
     */
    BudgetTemplate getTemplateById(UUID userId, UUID templateId);

    /**
     * REQ-5.3 A "Template Isolation &amp; Copy-on-Instantiate": creates a budget for the target
     * month, seeding its lines from the template (or, when {@code templateId} is null, from the
     * user's most recent active budget — "Previous Month Rollover").
     *
     * <p>The copy is one-way. The instantiated {@code ledger.budget_lines} are independent rows;
     * editing or deleting them never mutates the source template, and editing the template never
     * reaches back into budgets already created from it.
     *
     * @throws com.fintracker.ledger.shared.exception.ResourceNotFoundException
     *         {@code templateId} does not exist or is not visible to the user.
     * @throws com.fintracker.ledger.budget.exception.LineItemLimitExceededException
     *         template lines merged with overrides exceed 50 (REQ-5.3 B "Target Line Ceiling").
     * @throws com.fintracker.ledger.budget.exception.InvalidBudgetException
     *         monetary scale or range violations in the payload.
     * @throws com.fintracker.ledger.budget.exception.HistoricalBudgetException
     *         the target month's budget already exists and is CLOSED.
     */
    Budget instantiateQuickStartBudget(UUID userId, QuickStartBudgetRequest request);

    /**
     * Saves a set of category allocations as a reusable custom template owned by the user — the
     * "Save as Template" path.
     *
     * <p>Only custom templates can be created this way. The system catalog is seeded by migration
     * and the {@code budget_templates_insert} RLS policy rejects an unowned insert outright, so
     * no request-scoped session can manufacture a global template.
     *
     * @param name  unique per user, case-insensitive; max 100 characters.
     * @param lines the allocations to store; at most 50, categories unique case-insensitively.
     *
     * @throws com.fintracker.ledger.budget.exception.DuplicateTemplateException
     *         the user already owns a template with this name (REQ-5.3 B).
     * @throws com.fintracker.ledger.budget.exception.LineItemLimitExceededException
     *         more than 50 lines (REQ-5.3 B "Template Limit Ceiling").
     * @throws com.fintracker.ledger.budget.exception.InvalidBudgetException
     *         blank or oversized name, duplicate categories, or a limit outside REQ-5.1's
     *         monetary range and scale.
     */
    BudgetTemplate createCustomTemplate(UUID userId, String name, String description,
                                        List<BudgetTemplateLine> lines);
}
