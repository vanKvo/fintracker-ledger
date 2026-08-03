package com.fintracker.ledger.budget.validation;

import com.fintracker.ledger.budget.exception.InvalidBudgetException;

import java.math.BigDecimal;

/**
 * REQ-5.1 / REQ-5.2 Constraints: the single source of truth for the {@code limitAmount} range and
 * scale rules, shared by whole-budget upserts ({@code BudgetServiceImpl}) and single-line-item
 * writes ({@code BudgetLineServiceImpl}) so the two call sites can never drift apart on what a
 * valid limit is.
 */
public final class BudgetMonetaryPolicy {

    /** Inclusive lower bound for every line's limitAmount. */
    public static final BigDecimal MIN_LIMIT_AMOUNT = new BigDecimal("0.00");

    /** Inclusive upper bound for every line's limitAmount. */
    public static final BigDecimal MAX_LIMIT_AMOUNT = new BigDecimal("999999999.99");

    /** Monetary amounts carry at most 2 decimal places (cents). */
    public static final int MAX_MONETARY_SCALE = 2;

    private BudgetMonetaryPolicy() {
    }

    /**
     * @throws InvalidBudgetException if {@code limitAmount} is null, carries more than 2 decimal
     *                                places, or falls outside [0.00, 999999999.99]. Amounts are
     *                                never silently rounded — an over-precise value is rejected,
     *                                not truncated.
     */
    public static void validate(String category, BigDecimal limitAmount) {
        if (limitAmount == null) {
            throw new InvalidBudgetException(
                    "Category '%s' requires a limitAmount.".formatted(category));
        }
        // Deliberately the literal scale of the payload value, NOT stripTrailingZeros(): a client
        // sending "100.000" wrote 3 decimal places and must be rejected, even though the trailing
        // zeros are numerically insignificant (stripping would also collapse "100.000" to 1E+2,
        // scale -2, masking the violation entirely).
        if (limitAmount.scale() > MAX_MONETARY_SCALE) {
            throw new InvalidBudgetException(
                    "Category '%s' has limitAmount %s with more than 2 decimal places; amounts are not rounded."
                            .formatted(category, limitAmount.toPlainString()));
        }
        if (limitAmount.compareTo(MIN_LIMIT_AMOUNT) < 0 || limitAmount.compareTo(MAX_LIMIT_AMOUNT) > 0) {
            throw new InvalidBudgetException(
                    "Category '%s' has limitAmount %s outside the allowed range [%s, %s]."
                            .formatted(category, limitAmount.toPlainString(),
                                    MIN_LIMIT_AMOUNT.toPlainString(), MAX_LIMIT_AMOUNT.toPlainString()));
        }
    }
}
