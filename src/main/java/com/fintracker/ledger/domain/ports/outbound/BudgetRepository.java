package com.fintracker.ledger.domain.ports.outbound;

import com.fintracker.ledger.domain.model.Budget;
import com.fintracker.ledger.domain.model.BudgetLine;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound persistence port for the Budget aggregate. */
public interface BudgetRepository {

    Optional<Budget> findByUserAndMonth(UUID userId, LocalDate effectiveMonth);

    /** Returns the most recent budget for a user (for cloning into the next month). */
    Optional<Budget> findLatestByUserId(UUID userId);

    Budget save(Budget budget);

    void updateLines(UUID budgetId, List<BudgetLine> lines);
}
