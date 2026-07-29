package com.fintracker.ledger.budget.service;

import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BudgetService {

    Budget getBudgetForMonth(UUID userId, LocalDate effectiveMonth);

    /**
     * REQ-5.1: creates a new budget or updates an existing one for the specified month.
     * Initializes status as ACTIVE regardless of whether the month is past, present or future.
     *
     * @param userId     owning user.
     * @param month      target period; normalized to the 1st of the month before persistence.
     * @param templateId optional template to seed line items from; may be null.
     * @param lines      category limits to establish or overwrite.
     *
     * @throws com.fintracker.ledger.budget.exception.InvalidBudgetException
     *         range, scale, blank-category or duplicate-category violations.
     * @throws com.fintracker.ledger.budget.exception.HistoricalBudgetException
     *         the target budget's status is CLOSED.
     * @throws com.fintracker.ledger.budget.exception.LineItemLimitExceededException
     *         more than 50 line items.
     */
    Budget upsertBudget(UUID userId, LocalDate month, UUID templateId, List<BudgetLine> lines);

    /**
     * REQ-5.1 "Reopening Exemption": transitions a CLOSED budget back to ACTIVE.
     *
     * @throws com.fintracker.ledger.shared.exception.ResourceNotFoundException
     *         the budget does not exist or does not belong to the user.
     */
    Budget reopenBudget(UUID userId, UUID budgetId);

    /**
     * REQ-5.1 "Manual Close": transitions an ACTIVE budget to CLOSED ahead of month-end.
     *
     * @throws com.fintracker.ledger.shared.exception.ResourceNotFoundException
     *         the budget does not exist or does not belong to the user.
     * @throws com.fintracker.ledger.budget.exception.HistoricalBudgetException
     *         the budget is already CLOSED.
     */
    Budget closeBudget(UUID userId, UUID budgetId);

    /**
     * REQ-5.1 "Automated Period Closure": batch-transitions every ACTIVE budget whose
     * effectiveMonth is strictly before {@code cutoffDate} to CLOSED, across all users.
     * Invoked by the month-end scheduler; not exposed over REST.
     *
     * @return the number of budgets transitioned.
     */
    int closePastBudgets(LocalDate cutoffDate);
}
