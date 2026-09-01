package com.fintracker.ledger.budget.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REQ-5.3 F.3 payload for {@code POST /budgets/quick-start}.
 *
 * <p><b>Deviation from the written contract, deliberate.</b> REQ-5.3 F.3 types
 * {@code effectiveMonth} as {@code String (YYYY-MM)}. Every other budget endpoint, REQ-5.1's
 * "Normalization Rule" and the {@code ledger.budgets.effective_month DATE} column all use a
 * {@code LocalDate} normalized to {@code YYYY-MM-01}. Accepting {@code YYYY-MM} only here would
 * give one module two wire formats for the same concept; the field is typed {@code LocalDate}
 * and normalized like every other month in the module.
 *
 * @param totalBudgetCap  optional ceiling for the instantiated budget as a whole.
 * @param customOverrides explicit lines appended to (or overriding) the template's defaults.
 */
public record QuickStartBudgetRequest(
        @NotNull LocalDate effectiveMonth,
        UUID templateId,

        @DecimalMin("0.00")
        @DecimalMax("999999999.99")
        @Digits(integer = 9, fraction = 2)
        BigDecimal totalBudgetCap,

        List<@Valid CustomOverride> customOverrides
) {
    /**
     * REQ-5.3 F.3: {@code categoryName} / {@code limitAmount}. Note the asymmetry the requirement
     * itself introduces — an override carries a {@code limitAmount} (a live ceiling) while a
     * template line carries a {@code defaultLimit}.
     */
    public record CustomOverride(
            @NotBlank @Size(max = 50) String categoryName,

            @NotNull
            @DecimalMin("0.00")
            @DecimalMax("999999999.99")
            @Digits(integer = 9, fraction = 2)
            BigDecimal limitAmount
    ) {}
}
