package com.fintracker.ledger.budget.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * REQ-5.3 "Quick Start Templates": a reusable set of category allocations used to seed a new
 * budget. Backed by {@code ledger.budget_templates}.
 *
 * @param userId   owner of a custom template; {@code null} for a global system template, which is
 *                 what {@code isSystem} reflects.
 * @param isSystem true for the predefined global catalog ("Basic Living", "Aggressive Savings").
 */
public record BudgetTemplate(
        UUID templateId,
        UUID userId,
        String name,
        String description,
        boolean isSystem,
        List<BudgetTemplateLine> lines,
        OffsetDateTime createdAt
) {}
