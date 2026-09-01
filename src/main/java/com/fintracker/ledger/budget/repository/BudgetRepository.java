package com.fintracker.ledger.budget.repository;

import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.model.BudgetStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetRepository {

    Optional<Budget> findById(UUID budgetId);

    Optional<Budget> findByUserAndMonth(UUID userId, LocalDate effectiveMonth);

    /**
     * REQ-5.1 A.2 "Get Budgets": every budget of the user whose effective month falls within
     * {@code [startInclusive, endInclusive]}, most recent month first.
     *
     * <p>A pure read — it never creates a budget for a month that has none. Line items for the
     * whole result set are loaded in one additional query rather than one per budget, so the cost
     * is constant in the number of budgets returned.
     *
     * @return the matching budgets, ordered by effectiveMonth descending; empty when none match.
     */
    List<Budget> findByUserAndMonthRange(UUID userId, LocalDate startInclusive, LocalDate endInclusive);

    /**
     * Every distinct calendar year in which the user holds at least one budget, most recent first.
     *
     * <p>Supports the Budgets page's year navigation: the client needs the user's budget history
     * span before it can decide which years to offer, and an empty result is the authoritative
     * "this user has never created a budget" signal. Answering it by probing
     * {@link #findByUserAndMonthRange} year by year would be an unbounded number of queries
     * against an unknown lower bound.
     *
     * @return distinct years descending; empty when the user has no budgets at all.
     */
    List<Integer> findBudgetYears(UUID userId);

    /**
     * REQ-5.1 A.3 "Delete Budget": deletes the budget only if it is owned by {@code userId} and
     * still {@link BudgetStatus#ACTIVE}. Its line items go with it via the
     * {@code budget_lines.budget_id} ON DELETE CASCADE.
     *
     * <p>Ownership and the status guard are part of the DELETE's own WHERE clause rather than a
     * preceding SELECT, so no window exists in which a budget can be closed, reassigned or
     * deleted by a concurrent request between the check and the write. A caller that reads 0 here
     * must re-read to decide which failure it was.
     *
     * @return 1 when the budget was deleted, 0 when it did not match all three conditions.
     */
    int deleteByIdIfActive(UUID budgetId, UUID userId);

    Optional<Budget> findLatestByUserId(UUID userId);

    /**
     * REQ-5.1 "Template Inheritance": the most recent budget of the user that is still
     * {@link BudgetStatus#ACTIVE}, used to seed a lazily-created budget for a new month.
     */
    Optional<Budget> findLatestActiveByUserId(UUID userId);

    Budget save(Budget budget);

    /**
     * Atomically replaces every line of the budget with {@code lines} and bumps the budget's
     * {@code version} so the rewrite is visible to optimistic readers.
     */
    void updateLines(UUID budgetId, List<BudgetLine> lines);

    /**
     * REQ-5.1 "Manual Close" / "Reopening Exemption": transitions a single budget's status.
     */
    void updateStatus(UUID budgetId, BudgetStatus status);

    /**
     * REQ-5.1 "Automated Period Closure": closes every ACTIVE budget whose effective month is
     * strictly before {@code cutoffDate}, across all users.
     *
     * <p>This is a system-wide batch statement spanning multiple tenants. Row visibility during
     * the scan phase and update permission are granted by the {@code budgets_system_batch_select}
     * and {@code budgets_system_batch_update} RLS policies (V8), activated for the duration of
     * the batch transaction via {@code SET LOCAL app.system_job = 'true'} — the tenant-isolation
     * policy alone would hide every row from a session with no user context.
     *
     * @return the number of budgets transitioned to CLOSED.
     */
    int closeAllBefore(LocalDate cutoffDate);

    // ------------------------------------------------------- REQ-5.2 line-item operations

    /** A single line item, scoped to the budget it must belong to. */
    Optional<BudgetLine> findLineById(UUID budgetId, UUID lineId);

    /** Current number of line items on the budget — REQ-5.2 "Line Item Ceiling" (max 50). */
    int countLines(UUID budgetId);

    /**
     * REQ-5.2 "Category Uniqueness": whether {@code category} already exists on the budget,
     * compared case-insensitively. {@code excludeLineId}, when non-null, excludes that line from
     * the check (a line being renamed does not conflict with itself).
     */
    boolean existsCategoryIgnoreCase(UUID budgetId, String category, UUID excludeLineId);

    /** Inserts a single new line item and bumps the parent budget's version. */
    BudgetLine insertLine(UUID budgetId, BudgetLine line);

    /** Updates one line item's limitAmount and bumps the parent budget's version. */
    void updateLineLimit(UUID lineId, BigDecimal newLimitAmount);

    /** Deletes one line item and bumps the parent budget's version. */
    void deleteLine(UUID lineId);
}
