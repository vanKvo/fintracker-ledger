package com.fintracker.ledger.budget.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * REQ-5.3 F.2: one default category allocation inside a template, backed by
 * {@code ledger.budget_template_lines}.
 *
 * <p>Field names follow the requirement's data contract ({@code categoryName} /
 * {@code defaultLimit}), which deliberately differs from {@link BudgetLine}'s
 * {@code category} / {@code limitAmount} — a template line is a default, not a live ceiling.
 * "Copy-on-Instantiate" is where one becomes the other.
 */
public record BudgetTemplateLine(
        UUID lineId,
        UUID templateId,
        String categoryName,
        BigDecimal defaultLimit
) {}
