package com.fintracker.ledger.budget.service.impl;

import com.fintracker.ledger.budget.dto.BudgetLineInput;
import com.fintracker.ledger.budget.exception.DuplicateCategoryException;
import com.fintracker.ledger.budget.exception.HistoricalBudgetException;
import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import com.fintracker.ledger.budget.exception.LineItemLimitExceededException;
import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.model.BudgetStatus;
import com.fintracker.ledger.budget.repository.BudgetRepository;
import com.fintracker.ledger.budget.service.BudgetLineService;
import com.fintracker.ledger.budget.service.BudgetSpendCalculator;
import com.fintracker.ledger.budget.validation.BudgetMonetaryPolicy;
import com.fintracker.ledger.shared.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class BudgetLineServiceImpl implements BudgetLineService {

    private static final Logger log = LoggerFactory.getLogger(BudgetLineServiceImpl.class);

    /** REQ-5.2 Constraints: "Non-Empty Category Name ... truncated to a maximum length of 50 characters." */
    private static final int MAX_CATEGORY_LENGTH = 50;

    private final BudgetRepository budgetRepository;
    private final BudgetSpendCalculator spendCalculator;

    public BudgetLineServiceImpl(BudgetRepository budgetRepository, BudgetSpendCalculator spendCalculator) {
        this.budgetRepository = budgetRepository;
        this.spendCalculator = spendCalculator;
    }

    @Override
    public BudgetLine addLineItem(UUID userId, UUID budgetId, BudgetLineInput lineInput) {
        var budget = findOwnedBudget(userId, budgetId);
        rejectIfClosed(budget);

        String category = normalizeCategory(lineInput.category());
        BudgetMonetaryPolicy.validate(category, lineInput.limitAmount());

        if (budgetRepository.existsCategoryIgnoreCase(budgetId, category, null)) {
            throw new DuplicateCategoryException(category);
        }
        int currentCount = budgetRepository.countLines(budgetId);
        if (currentCount + 1 > LineItemLimitExceededException.MAX_LINE_ITEMS) {
            throw new LineItemLimitExceededException(currentCount + 1);
        }

        var toInsert = new BudgetLine(null, budgetId, category, lineInput.limitAmount(), null, null);
        var saved = budgetRepository.insertLine(budgetId, toInsert);
        log.info("Added budget line. budgetId={} userId={} lineId={} category={}",
                budgetId, userId, saved.lineId(), category);

        return withComputedSpend(saved, userId, budget.effectiveMonth());
    }

    @Override
    public BudgetLine updateLineItemLimit(UUID userId, UUID budgetId, UUID lineId, BigDecimal newLimitAmount) {
        var budget = findOwnedBudget(userId, budgetId);
        rejectIfClosed(budget);
        var line = findOwnedLine(budgetId, lineId);

        BudgetMonetaryPolicy.validate(line.category(), newLimitAmount);

        budgetRepository.updateLineLimit(lineId, newLimitAmount);
        log.info("Updated budget line limit. budgetId={} userId={} lineId={} newLimitAmount={}",
                budgetId, userId, lineId, newLimitAmount);

        var updated = new BudgetLine(lineId, budgetId, line.category(), newLimitAmount, line.description(), null);
        return withComputedSpend(updated, userId, budget.effectiveMonth());
    }

    @Override
    public void removeLineItem(UUID userId, UUID budgetId, UUID lineId) {
        var budget = findOwnedBudget(userId, budgetId);
        rejectIfClosed(budget);
        findOwnedLine(budgetId, lineId);

        budgetRepository.deleteLine(lineId);
        log.info("Removed budget line. budgetId={} userId={} lineId={}", budgetId, userId, lineId);
    }

    /**
     * REQ-5.2 Constraints: "Non-Empty Category Name" — non-null, non-blank, truncated (not
     * rejected — unlike the monetary scale rule) to 50 characters.
     */
    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new InvalidBudgetException("Every budget line requires a non-blank category.");
        }
        String trimmed = category.strip();
        return trimmed.length() > MAX_CATEGORY_LENGTH ? trimmed.substring(0, MAX_CATEGORY_LENGTH) : trimmed;
    }

    /** REQ-5.2 "Dynamic Spend Initialization" — same future/current/past rule as REQ-5.1. */
    private BudgetLine withComputedSpend(BudgetLine line, UUID userId, LocalDate effectiveMonth) {
        BigDecimal spent = spendCalculator.computeSpent(userId, effectiveMonth, line.category());
        return new BudgetLine(line.lineId(), line.budgetId(), line.category(), line.limitAmount(),
                line.description(), spent);
    }

    /** REQ-5.2 "State Check Guard": every line write requires the parent budget be ACTIVE. */
    private void rejectIfClosed(Budget budget) {
        if (budget.status() == BudgetStatus.CLOSED) {
            throw new HistoricalBudgetException(budget.budgetId());
        }
    }

    private Budget findOwnedBudget(UUID userId, UUID budgetId) {
        if (userId == null || budgetId == null) {
            throw new ResourceNotFoundException("Budget", budgetId);
        }
        return budgetRepository.findById(budgetId)
                .filter(b -> b.userId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Budget", budgetId));
    }

    private BudgetLine findOwnedLine(UUID budgetId, UUID lineId) {
        if (lineId == null) {
            throw new ResourceNotFoundException("BudgetLine", null);
        }
        return budgetRepository.findLineById(budgetId, lineId)
                .orElseThrow(() -> new ResourceNotFoundException("BudgetLine", lineId));
    }
}
