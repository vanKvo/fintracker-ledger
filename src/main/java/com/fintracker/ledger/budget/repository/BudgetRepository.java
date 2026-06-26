package com.fintracker.ledger.budget.repository;

import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetRepository {

    Optional<Budget> findByUserAndMonth(UUID userId, LocalDate effectiveMonth);

    Optional<Budget> findLatestByUserId(UUID userId);

    Budget save(Budget budget);

    void updateLines(UUID budgetId, List<BudgetLine> lines);
}
