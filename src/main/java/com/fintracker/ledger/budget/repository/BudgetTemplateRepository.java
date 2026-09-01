package com.fintracker.ledger.budget.repository;

import com.fintracker.ledger.budget.model.BudgetTemplate;
import com.fintracker.ledger.budget.model.BudgetTemplateLine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** REQ-5.3 persistence port for {@code ledger.budget_templates}. */
public interface BudgetTemplateRepository {

    /**
     * REQ-5.3 A "System &amp; Custom Template Availability": every system template plus the
     * user's own, ordered system-first then by name.
     *
     * <p>The {@code userId} predicate is applied in the query as well as by RLS. Belt and braces:
     * the policy is the safety net, the query is the contract.
     */
    List<BudgetTemplate> findAvailable(UUID userId);

    /** A single visible template with its lines, or empty when it does not exist or is not visible. */
    Optional<BudgetTemplate> findVisibleById(UUID userId, UUID templateId);

    /** Whether the user already owns a template with this name, compared case-insensitively. */
    boolean existsByUserAndNameIgnoreCase(UUID userId, String name);

    /**
     * Persists a custom template and its lines atomically. System templates cannot be created
     * through this path — the {@code budget_templates_insert} RLS policy rejects a null owner.
     */
    BudgetTemplate saveCustom(BudgetTemplate template, List<BudgetTemplateLine> lines);
}
