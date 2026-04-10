package com.fintracker.ledger.domain.service;

import com.fintracker.ledger.domain.model.Budget;
import com.fintracker.ledger.domain.model.BudgetLine;
import com.fintracker.ledger.domain.ports.outbound.BudgetRepository;
import com.fintracker.ledger.domain.ports.outbound.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Domain Service: Budgets Module.
 * Enforces the immutability rule for past months and the auto-clone
 * logic when navigating to a month without an existing budget.
 */
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;

    public BudgetService(BudgetRepository budgetRepository,
                         TransactionRepository transactionRepository) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Retrieves the budget for a given month, enriched with actual spending per category.
     * If no budget exists, auto-creates a Base Template by cloning from the previous month.
     */
    public Budget getBudgetForMonth(UUID userId, LocalDate effectiveMonth) {
        LocalDate normalizedMonth = effectiveMonth.withDayOfMonth(1);

        return budgetRepository.findByUserAndMonth(userId, normalizedMonth)
                .map(b -> enrichWithSpending(b, userId, normalizedMonth))
                .orElseGet(() -> createBaseTemplate(userId, normalizedMonth));
    }

    /**
     * Creates or updates a budget. Rejects writes to past months.
     */
    public Budget upsertBudget(UUID userId, LocalDate effectiveMonth, List<BudgetLine> lines) {
        LocalDate normalizedMonth = effectiveMonth.withDayOfMonth(1);

        if (normalizedMonth.isBefore(LocalDate.now().withDayOfMonth(1))) {
            throw new PastMonthModificationException(normalizedMonth);
        }

        var existing = budgetRepository.findByUserAndMonth(userId, normalizedMonth);
        if (existing.isPresent()) {
            budgetRepository.updateLines(existing.get().budgetId(), lines);
            log.info("Updated budget lines for userId={} month={}", userId, normalizedMonth);
            return getBudgetForMonth(userId, normalizedMonth);
        }

        var newBudget = new Budget(null, userId, normalizedMonth, 1, null, lines, null);
        var saved = budgetRepository.save(newBudget);
        log.info("Created new budget budgetId={} month={}", saved.budgetId(), normalizedMonth);
        return enrichWithSpending(saved, userId, normalizedMonth);
    }

    private Budget enrichWithSpending(Budget budget, UUID userId, LocalDate month) {
        LocalDate monthEnd = month.plusMonths(1).minusDays(1);

        List<BudgetLine> enrichedLines = budget.lines().stream()
                .map(line -> {
                    BigDecimal spent = transactionRepository.sumMonthlyExpenses(userId, month, monthEnd);
                    return new BudgetLine(line.lineId(), line.budgetId(), line.category(), line.limitAmount(), line.description(), spent);
                })
                .toList();

        return new Budget(budget.budgetId(), budget.userId(), budget.effectiveMonth(),
                budget.version(), budget.description(), enrichedLines, budget.createdAt());
    }

    private Budget createBaseTemplate(UUID userId, LocalDate newMonth) {
        return budgetRepository.findLatestByUserId(userId)
                .map(previous -> {
                    log.info("Cloning previous budget as base template for month={}", newMonth);
                    var templateLines = previous.lines().stream()
                            .map(l -> new BudgetLine(null, null, l.category(), l.limitAmount(), l.description(), BigDecimal.ZERO))
                            .toList();
                    return budgetRepository.save(new Budget(null, userId, newMonth, 1, null, templateLines, null));
                })
                .orElse(new Budget(null, userId, newMonth, 1, null, List.of(), null));
    }

    public static class PastMonthModificationException extends RuntimeException {
        public PastMonthModificationException(LocalDate month) {
            super("Budget for past month %s is read-only and cannot be modified.".formatted(month));
        }
    }
}
