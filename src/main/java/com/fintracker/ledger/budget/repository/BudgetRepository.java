package com.fintracker.ledger.budget.repository;

import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.model.BudgetStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetRepository {

    Optional<Budget> findById(UUID budgetId);

    Optional<Budget> findByUserAndMonth(UUID userId, LocalDate effectiveMonth);

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
}
