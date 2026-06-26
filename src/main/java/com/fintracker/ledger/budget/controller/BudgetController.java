package com.fintracker.ledger.budget.controller;

import com.fintracker.ledger.budget.dto.UpsertBudgetRequest;
import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.service.BudgetService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger/budgets")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @GetMapping
    public ResponseEntity<Budget> getBudget(
            @RequestAttribute("userId") UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month
    ) {
        return ResponseEntity.ok(budgetService.getBudgetForMonth(userId, month));
    }

    @PostMapping
    public ResponseEntity<Budget> upsertBudget(
            @RequestAttribute("userId") UUID userId,
            @Valid @RequestBody UpsertBudgetRequest request
    ) {
        var lines = request.lines().stream()
                .map(l -> new BudgetLine(null, null, l.category(), l.limitAmount(), null, null))
                .toList();
        return ResponseEntity.ok(budgetService.upsertBudget(userId, request.effectiveMonth(), lines));
    }
}
