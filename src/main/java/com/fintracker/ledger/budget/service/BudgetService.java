package com.fintracker.ledger.budget.service;

import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BudgetService {

    Budget getBudgetForMonth(UUID userId, LocalDate effectiveMonth);

    Budget upsertBudget(UUID userId, LocalDate effectiveMonth, List<BudgetLine> lines);
}
