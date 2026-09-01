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
 *   <li>{@code GET  /api/v1/ledger/budgets?month=} — the budget of one month (200).</li>
 *   <li>{@code GET  /api/v1/ledger/budgets?year=} — every budget of a calendar year (200).</li>
 *   <li>{@code PUT /api/v1/ledger/budgets} — create (201) or update (200) the budget of a month.</li>
 *   <li>{@code DELETE /api/v1/ledger/budgets/{id}} — delete an ACTIVE budget (204).</li>
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

    /**
     * Single-month read. Mapped on {@code params = "month"} because this URL serves two shapes —
     * one budget object here, an array from {@link #getBudgetsForYear} — and Spring must pick
     * between them by which parameter the client sent, not by declaration order.
     */
    @GetMapping(params = "month")
    public ResponseEntity<Budget> getBudget(
            @RequestAttribute("userId") UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month
    ) {
        return ResponseEntity.ok(budgetService.getBudgetForMonth(userId, month));
    }

    /**
     * REQ-5.1 A.2 "Get Budgets" — every budget the user already holds in {@code year}, most recent
     * month first. A year with no budgets is 200 OK with an empty array, never 404: "this user has
     * no budgets in 2019" is a successful answer, not a missing resource.
     */
    @GetMapping(params = "year")
    public ResponseEntity<List<Budget>> getBudgetsForYear(
            @RequestAttribute("userId") UUID userId,
            @RequestParam int year
    ) {
        return ResponseEntity.ok(budgetService.getBudgetsForYear(userId, year));
    }

    /**
     * The years the user actually holds budgets in, most recent first — what the Budgets page
     * needs to build its year navigation before it knows anything else. An empty array is the
     * authoritative "no budgets created yet" signal, distinct from "the current year is empty".
     */
    @GetMapping("/years")
    public ResponseEntity<List<Integer>> getBudgetYears(@RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(budgetService.getBudgetYears(userId));
    }

    /**
     * REQ-5.1 A.3 "Delete Budget" — removes an ACTIVE budget and, by cascade, its line items.
     * 204 with no body: there is no post-deletion representation to return, and echoing the
     * deleted budget back would invite clients to treat it as still live.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBudget(
            @RequestAttribute("userId") UUID userId,
            @PathVariable("id") UUID budgetId
    ) {
        budgetService.deleteBudget(userId, budgetId);
        return ResponseEntity.noContent().build();
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
