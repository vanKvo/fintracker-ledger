package com.fintracker.ledger.budget.controller;

import com.fintracker.ledger.budget.dto.UpsertBudgetRequest;
import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.service.BudgetService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REQ-5.1 REST adapter for the budget module.
 *
 * <ul>
 *   <li>{@code PUT /api/v1/ledger/budgets} — create (201) or update (200) the budget of a month.</li>
 *   <li>{@code POST /api/v1/ledger/budgets/{id}/close} — ACTIVE → CLOSED (200).</li>
 *   <li>{@code POST /api/v1/ledger/budgets/{id}/reopen} — CLOSED → ACTIVE (200).</li>
 * </ul>
 */
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

    @PutMapping
    public ResponseEntity<Budget> upsertBudget(
            @RequestAttribute("userId") UUID userId,
            @Valid @RequestBody UpsertBudgetRequest request
    ) {
        List<BudgetLine> lines = request.lines() == null
                ? List.of()
                : request.lines().stream()
                        .map(l -> new BudgetLine(null, null, l.category(), l.limitAmount(), null, null))
                        .toList();

        boolean existed = budgetService.budgetExistsForMonth(userId, request.effectiveMonth());
        Budget budget = budgetService.upsertBudget(userId, request.effectiveMonth(), request.templateId(), lines);
        return ResponseEntity.status(existed ? HttpStatus.OK : HttpStatus.CREATED).body(budget);
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<Budget> closeBudget(
            @RequestAttribute("userId") UUID userId,
            @PathVariable("id") UUID budgetId
    ) {
        return ResponseEntity.ok(budgetService.closeBudget(userId, budgetId));
    }

    @PostMapping("/{id}/reopen")
    public ResponseEntity<Budget> reopenBudget(
            @RequestAttribute("userId") UUID userId,
            @PathVariable("id") UUID budgetId
    ) {
        return ResponseEntity.ok(budgetService.reopenBudget(userId, budgetId));
    }
}
