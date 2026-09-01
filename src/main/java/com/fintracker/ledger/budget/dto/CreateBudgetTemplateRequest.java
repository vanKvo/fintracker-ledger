package com.fintracker.ledger.budget.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Payload for {@code POST /budget-templates} — the "Save as Template" action.
 *
 * <p>Supplying {@code sourceBudgetId} is the normal path: the server reads that budget's own line
 * items rather than trusting amounts echoed back by the client, so a template can never be saved
 * with figures the budget does not actually contain. Explicit {@code lines} take precedence when
 * both are present, mirroring REQ-5.1 "Template Inheritance", where an explicit payload always
 * wins over a template reference.
 */
public record CreateBudgetTemplateRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 255) String description,
        UUID sourceBudgetId,
        List<@Valid TemplateLineRequest> lines
) {
    public record TemplateLineRequest(
            @NotBlank @Size(max = 50) String categoryName,

            @NotNull
            @DecimalMin("0.00")
            @DecimalMax("999999999.99")
            @Digits(integer = 9, fraction = 2)
            BigDecimal defaultLimit
    ) {}
}
