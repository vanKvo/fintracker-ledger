package com.fintracker.ledger.budget.service;

import com.fintracker.ledger.transaction.service.TransactionService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * REQ-5.1 "Spend Amount Initialization" / REQ-5.2 "Dynamic Spend Initialization": a future
 * period's {@code spentAmount} is always {@code $0.00}; a current or past period's is the sum of
 * the user's approved transactions in that category across the period. Both the whole-budget
 * enrichment path ({@code BudgetServiceImpl}) and the single-line-item write path
 * ({@code BudgetLineServiceImpl}) resolve through this one component so "is this period in the
 * future" is decided identically everywhere, off the same injected {@link Clock}.
 */
@Component
public class BudgetSpendCalculator {

    private final TransactionService transactionService;
    private final Clock clock;

    public BudgetSpendCalculator(TransactionService transactionService, Clock clock) {
        this.transactionService = transactionService;
        this.clock = clock;
    }

    /** The first day of the month the injected {@link Clock} is currently in. */
    public LocalDate currentMonth() {
        return LocalDate.now(clock).withDayOfMonth(1);
    }

    /**
     * @param effectiveMonth the budget period's first-of-month date.
     * @param category       the line item's category to aggregate approved transactions for.
     * @return {@link BigDecimal#ZERO} for a period after the current month; otherwise the sum of
     * approved expenses in {@code category} across {@code effectiveMonth}.
     */
    public BigDecimal computeSpent(UUID userId, LocalDate effectiveMonth, String category) {
        if (effectiveMonth.isAfter(currentMonth())) {
            return BigDecimal.ZERO;
        }
        LocalDate monthEnd = effectiveMonth.plusMonths(1).minusDays(1);
        return transactionService.sumMonthlyExpensesPerCategory(userId, effectiveMonth, monthEnd, category);
    }
}
