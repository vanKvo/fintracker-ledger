package com.fintracker.ledger.budget.controller;

import com.fintracker.ledger.budget.dto.AddLineItemRequest;
import com.fintracker.ledger.budget.dto.BudgetLineInput;
import com.fintracker.ledger.budget.dto.UpdateLineItemLimitRequest;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.service.BudgetLineService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REQ-5.2 REST adapter for granular budget line-item operations.
 *
 * <ul>
 *   <li>{@code POST /api/v1/ledger/budgets/{budgetId}/lines} — add a line item (201).</li>
 *   <li>{@code PUT /api/v1/ledger/budgets/{budgetId}/lines/{lineId}} — update a line item's limit (200).</li>
 *   <li>{@code DELETE /api/v1/ledger/budgets/{budgetId}/lines/{lineId}} — remove a line item (200).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/ledger/budgets/{budgetId}/lines")
public class BudgetLineController {

    private final BudgetLineService budgetLineService;

    public BudgetLineController(BudgetLineService budgetLineService) {
        this.budgetLineService = budgetLineService;
    }

    @PostMapping
    public ResponseEntity<BudgetLine> addLineItem(
            @RequestAttribute("userId") UUID userId,
            @PathVariable UUID budgetId,
            @Valid @RequestBody AddLineItemRequest request
    ) {
        BudgetLine line = budgetLineService.addLineItem(
                userId, budgetId, new BudgetLineInput(request.category(), request.limitAmount()));
        return ResponseEntity.status(HttpStatus.CREATED).body(line);
    }

    @PutMapping("/{lineId}")
    public ResponseEntity<BudgetLine> updateLineItemLimit(
            @RequestAttribute("userId") UUID userId,
            @PathVariable UUID budgetId,
            @PathVariable UUID lineId,
            @Valid @RequestBody UpdateLineItemLimitRequest request
    ) {
        BudgetLine line = budgetLineService.updateLineItemLimit(userId, budgetId, lineId, request.limitAmount());
        return ResponseEntity.ok(line);
    }

    @DeleteMapping("/{lineId}")
    public ResponseEntity<Void> removeLineItem(
            @RequestAttribute("userId") UUID userId,
            @PathVariable UUID budgetId,
            @PathVariable UUID lineId
    ) {
        budgetLineService.removeLineItem(userId, budgetId, lineId);
        return ResponseEntity.ok().build();
    }
}
