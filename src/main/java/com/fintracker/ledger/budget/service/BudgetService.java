package com.fintracker.ledger.budget.service;

import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BudgetService {

    Budget getBudgetForMonth(UUID userId, LocalDate effectiveMonth);

    /**
     * Read-only existence probe for the normalized target month. Lets the REST layer
     * distinguish 201 Created (new budget) from 200 OK (update) without triggering the
     * lazy-creation behavior of {@link #getBudgetForMonth}.
     */
    boolean budgetExistsForMonth(UUID userId, LocalDate month);

    /**
     * REQ-5.1: creates a new budget or updates an existing budget for the specified month.
     * Initializes status as ACTIVE regardless of whether the month is past, present or future.
     *
     * @param userId     unique identifier of the target user account.
     * @param month      target period; normalized to the 1st of the month before persistence.
     * @param templateId optional ID of an existing budget of the same user whose line items seed
     *                   this budget when no explicit {@code lines} are supplied; may be null.
     * @param lines      category limits to establish or overwrite; take precedence over the
     *                   template. Null or empty (with no template) creates an empty budget.
     * @return the persisted budget.
     *
     * @throws com.fintracker.ledger.budget.exception.InvalidBudgetException
     *         range, scale, blank-category, duplicate-category or unknown-template violations.
     * @throws com.fintracker.ledger.budget.exception.HistoricalBudgetException
     *         the target budget's status is CLOSED.
     * @throws com.fintracker.ledger.budget.exception.LineItemLimitExceededException
     *         more than 50 line items.
     */
    Budget upsertBudget(UUID userId, LocalDate month, UUID templateId, List<BudgetLine> lines);

    /**
     * A single budget by id, scoped to its owner.
     *
     * @throws com.fintracker.ledger.shared.exception.ResourceNotFoundException
     *         the budget does not exist or belongs to another user — deliberately the same answer
     *         for both, so an id probe cannot confirm someone else's budget exists.
     */
    Budget getBudgetById(UUID userId, UUID budgetId);

    /**
     * REQ-5.1 A.2 "Get Budgets": every budget the user already has in the given calendar year,
     * most recent month first, each enriched with per-line {@code spentAmount} under the same
     * rules as {@link #getBudgetForMonth}.
     *
     * <p>Unlike {@link #getBudgetForMonth}, this is a <em>pure read</em>: it never materializes a
     * budget for a month that has none. Listing a year the user has never budgeted in must not
     * leave twelve budgets behind.
     *
     * @param userId unique identifier of the requesting user.
     * @param year   four-digit calendar year; budgets whose normalized effectiveMonth falls in
     *               {@code [YYYY-01-01, YYYY-12-01]} inclusive are returned.
     * @return the user's budgets for that year ordered by effectiveMonth descending; an empty
     *         list when the year has none — never null.
     *
     * @throws com.fintracker.ledger.budget.exception.InvalidBudgetException
     *         {@code userId} is null, or {@code year} is outside [1970, 9999].
     */
    List<Budget> getBudgetsForYear(UUID userId, int year);

    /**
     * Every calendar year in which the user holds at least one budget, most recent first.
     *
     * <p>Companion to {@link #getBudgetsForYear}: it tells a client which years are worth asking
     * about before it asks. The first element is the user's most recent budget year and the last
     * is the year they started budgeting; an empty list means they have never created one.
     *
     * @throws com.fintracker.ledger.budget.exception.InvalidBudgetException {@code userId} is null.
     */
    List<Integer> getBudgetYears(UUID userId);

    /**
     * REQ-5.1 A.3 "Delete Budget": permanently removes an ACTIVE budget and, by cascade, its line
     * items. Underlying transactions are never altered — REQ-5.2 "Orphaned Transaction Handling"
     * applies to whole-budget deletion exactly as it does to a single line.
     *
     * <p>Deletion is a write, so REQ-5.1 "Immutability upon Closure" governs it: a CLOSED budget
     * is historical record and must be explicitly reopened before it can be deleted.
     *
     * @throws com.fintracker.ledger.shared.exception.ResourceNotFoundException
     *         the budget does not exist or does not belong to the user — the two cases are
     *         deliberately indistinguishable to the caller.
     * @throws com.fintracker.ledger.budget.exception.HistoricalBudgetException
     *         the budget's status is CLOSED.
     */
    void deleteBudget(UUID userId, UUID budgetId);

    /**
     * REQ-5.1 "Reopening Exemption": transitions a CLOSED budget back to ACTIVE so the user can
     * modify it again. Reopening an already ACTIVE budget is a no-op.
     *
     * @throws com.fintracker.ledger.shared.exception.ResourceNotFoundException
     *         the budget does not exist or does not belong to the user.
     */
    Budget reopenBudget(UUID userId, UUID budgetId);

    /**
     * REQ-5.1 "Template Inheritance": retrieves the budget for the target month, or lazily
     * creates one by copying the line items of the user's most recent ACTIVE budget (an empty
     * ACTIVE budget when none exists).
     *
     * @throws com.fintracker.ledger.budget.exception.InvalidBudgetException
     *         missing input parameters.
     */
    Budget getOrCreateBudgetFromPrevious(UUID userId, LocalDate targetMonth);

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
     * @param cutoffDate target month threshold (normalized to the 1st of its month; typically
     *                   the start of the current month).
     * @return the number of budgets transitioned.
     *
     * @throws NullPointerException if {@code cutoffDate} is null — a programming error of the
     *         system caller, never a client-facing 4xx condition.
     */
    int closePastBudgets(LocalDate cutoffDate);
}
