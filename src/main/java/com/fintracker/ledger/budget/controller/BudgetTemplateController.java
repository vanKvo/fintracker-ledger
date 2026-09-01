package com.fintracker.ledger.budget.controller;

import com.fintracker.ledger.budget.dto.CreateBudgetTemplateRequest;
import com.fintracker.ledger.budget.dto.QuickStartBudgetRequest;
import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetTemplate;
import com.fintracker.ledger.budget.model.BudgetTemplateLine;
import com.fintracker.ledger.budget.service.BudgetService;
import com.fintracker.ledger.budget.service.BudgetTemplateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REQ-5.3 D REST adapter for the budget template catalog.
 *
 * <ul>
 *   <li>{@code GET  /api/v1/ledger/budget-templates} — system + own custom templates (200).</li>
 *   <li>{@code GET  /api/v1/ledger/budget-templates/{id}} — one template with its lines (200).</li>
 *   <li>{@code POST /api/v1/ledger/budget-templates} — save a custom template (201).</li>
 *   <li>{@code POST /api/v1/ledger/budgets/quick-start} — instantiate a budget (201).</li>
 * </ul>
 *
 * <p><b>Path note.</b> REQ-5.3 writes these as {@code /api/v1/budget-templates} and
 * {@code /api/v1/budgets/quick-start}. Every controller in this service is mounted under
 * {@code /api/v1/ledger/*}; these follow that convention, as {@link BudgetController} already
 * does for REQ-5.1.
 */
@RestController
@RequestMapping("/api/v1/ledger")
public class BudgetTemplateController {

    private final BudgetTemplateService templateService;
    private final BudgetService budgetService;

    public BudgetTemplateController(BudgetTemplateService templateService,
                                    BudgetService budgetService) {
        this.templateService = templateService;
        this.budgetService = budgetService;
    }

    @GetMapping("/budget-templates")
    public ResponseEntity<List<BudgetTemplate>> listTemplates(
            @RequestAttribute("userId") UUID userId
    ) {
        return ResponseEntity.ok(templateService.getAvailableTemplates(userId));
    }

    @GetMapping("/budget-templates/{templateId}")
    public ResponseEntity<BudgetTemplate> getTemplate(
            @RequestAttribute("userId") UUID userId,
            @PathVariable UUID templateId
    ) {
        return ResponseEntity.ok(templateService.getTemplateById(userId, templateId));
    }

    /**
     * "Save as Template". When {@code sourceBudgetId} is given and no explicit lines are supplied,
     * the allocations are read from that budget server-side — the client never dictates the
     * figures a template is saved with.
     */
    @PostMapping("/budget-templates")
    public ResponseEntity<BudgetTemplate> createTemplate(
            @RequestAttribute("userId") UUID userId,
            @Valid @RequestBody CreateBudgetTemplateRequest request
    ) {
        List<BudgetTemplateLine> lines = resolveLines(userId, request);
        var created = templateService.createCustomTemplate(
                userId, request.name(), request.description(), lines);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/budgets/quick-start")
    public ResponseEntity<Budget> quickStart(
            @RequestAttribute("userId") UUID userId,
            @Valid @RequestBody QuickStartBudgetRequest request
    ) {
        var budget = templateService.instantiateQuickStartBudget(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(budget);
    }

    private List<BudgetTemplateLine> resolveLines(UUID userId, CreateBudgetTemplateRequest request) {
        if (request.lines() != null && !request.lines().isEmpty()) {
            return request.lines().stream()
                    .map(l -> new BudgetTemplateLine(null, null, l.categoryName(), l.defaultLimit()))
                    .toList();
        }
        if (request.sourceBudgetId() == null) {
            return List.of();
        }
        // Ownership is enforced by getBudgetForMonth's caller chain; findOwnedBudget semantics
        // mean another user's budgetId surfaces as 404 rather than leaking its contents.
        var source = budgetService.getBudgetById(userId, request.sourceBudgetId());
        return source.lines().stream()
                .map(l -> new BudgetTemplateLine(null, null, l.category(), l.limitAmount()))
                .toList();
    }
}
