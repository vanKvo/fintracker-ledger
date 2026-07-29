package com.fintracker.ledger.budget.service.impl;

import com.fintracker.ledger.budget.exception.HistoricalBudgetException;
import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import com.fintracker.ledger.budget.exception.LineItemLimitExceededException;
import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.model.BudgetStatus;
import com.fintracker.ledger.budget.repository.BudgetRepository;
import com.fintracker.ledger.budget.service.BudgetService;
import com.fintracker.ledger.shared.exception.ResourceNotFoundException;
import com.fintracker.ledger.transaction.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class BudgetServiceImpl implements BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetServiceImpl.class);

    /** REQ-5.1 Constraints: inclusive bounds for every line's limitAmount. */
    private static final BigDecimal MIN_LIMIT_AMOUNT = new BigDecimal("0.00");
    private static final BigDecimal MAX_LIMIT_AMOUNT = new BigDecimal("999999999.99");

    /** REQ-5.1 Constraints: monetary amounts carry at most 2 decimal places (cents). */
    private static final int MAX_MONETARY_SCALE = 2;

    private final BudgetRepository budgetRepository;
    private final TransactionService transactionService;
    private final Clock clock;

    public BudgetServiceImpl(BudgetRepository budgetRepository,
                             TransactionService transactionService,
                             Clock clock) {
        this.budgetRepository = budgetRepository;
        this.transactionService = transactionService;
        this.clock = clock;
    }

    /**
     * REQ-5.1: the first day of the month the injected {@link Clock} is currently in. Every
     * past / current / future classification in this service resolves through here so the
     * behavior is deterministic and zone-independent — never {@code LocalDate.now()}.
     */
    private LocalDate currentMonth() {
        return LocalDate.now(clock).withDayOfMonth(1);
    }

    @Override
    public Budget getBudgetForMonth(UUID userId, LocalDate effectiveMonth) {
        return getOrCreateBudgetFromPrevious(userId, effectiveMonth);
    }

    @Override
    public boolean budgetExistsForMonth(UUID userId, LocalDate month) {
        requireInputs(userId, month);
        return budgetRepository.findByUserAndMonth(userId, month.withDayOfMonth(1)).isPresent();
    }

    @Override
    public Budget upsertBudget(UUID userId, LocalDate effectiveMonth, UUID templateId, List<BudgetLine> lines) {
        requireInputs(userId, effectiveMonth);
        LocalDate normalizedMonth = effectiveMonth.withDayOfMonth(1);

        validateLines(lines);
        List<BudgetLine> effectiveLines = resolveEffectiveLines(userId, templateId, lines);

        var existing = budgetRepository.findByUserAndMonth(userId, normalizedMonth);
        if (existing.isPresent()) {
            var budget = existing.get();
            rejectIfClosed(budget);
            budgetRepository.updateLines(budget.budgetId(), effectiveLines);
            log.info("Updated budget lines. budgetId={} userId={} month={} lineCount={}",
                    budget.budgetId(), userId, normalizedMonth, effectiveLines.size());
            return enrichWithSpending(
                    budgetRepository.findById(budget.budgetId()).orElseThrow(
                            () -> new ResourceNotFoundException("Budget", budget.budgetId())),
                    userId, normalizedMonth);
        }

        var newBudget = new Budget(null, userId, normalizedMonth, 1, BudgetStatus.ACTIVE, null, effectiveLines, null);
        var saved = budgetRepository.save(newBudget);
        log.info("Created new budget. budgetId={} userId={} month={} lineCount={}",
                saved.budgetId(), userId, normalizedMonth, effectiveLines.size());
        return enrichWithSpending(saved, userId, normalizedMonth);
    }

    @Override
    public Budget reopenBudget(UUID userId, UUID budgetId) {
        var budget = findOwnedBudget(userId, budgetId);
        if (budget.status() == BudgetStatus.ACTIVE) {
            log.debug("Budget already ACTIVE; reopen is a no-op. budgetId={}", budgetId);
            return budget;
        }
        budgetRepository.updateStatus(budgetId, BudgetStatus.ACTIVE);
        log.info("Reopened budget. budgetId={} userId={}", budgetId, userId);
        return findOwnedBudget(userId, budgetId);
    }

    @Override
    public Budget closeBudget(UUID userId, UUID budgetId) {
        var budget = findOwnedBudget(userId, budgetId);
        if (budget.status() == BudgetStatus.CLOSED) {
            throw new HistoricalBudgetException("Budget %s is already CLOSED.".formatted(budgetId));
        }
        budgetRepository.updateStatus(budgetId, BudgetStatus.CLOSED);
        log.info("Closed budget. budgetId={} userId={}", budgetId, userId);
        return findOwnedBudget(userId, budgetId);
    }

    @Override
    public int closePastBudgets(LocalDate cutoffDate) {
        Objects.requireNonNull(cutoffDate, "cutoffDate is required");
        LocalDate normalizedCutoff = cutoffDate.withDayOfMonth(1);
        int closed = budgetRepository.closeAllBefore(normalizedCutoff);
        log.info("Automated period closure transitioned {} budget(s) to CLOSED. cutoff={}",
                closed, normalizedCutoff);
        return closed;
    }

    @Override
    public Budget getOrCreateBudgetFromPrevious(UUID userId, LocalDate targetMonth) {
        requireInputs(userId, targetMonth);
        LocalDate normalizedMonth = targetMonth.withDayOfMonth(1);

        return budgetRepository.findByUserAndMonth(userId, normalizedMonth)
                .map(b -> enrichWithSpending(b, userId, normalizedMonth))
                .orElseGet(() -> createFromPrevious(userId, normalizedMonth));
    }

    private Budget createFromPrevious(UUID userId, LocalDate newMonth) {
        var templateLines = budgetRepository.findLatestActiveByUserId(userId)
                .map(previous -> {
                    log.info("Cloning most recent active budget {} as base for month={}",
                            previous.budgetId(), newMonth);
                    return cloneLines(previous.lines());
                })
                .orElseGet(List::of);

        var saved = budgetRepository.save(
                new Budget(null, userId, newMonth, 1, BudgetStatus.ACTIVE, null, templateLines, null));
        log.info("Lazily created budget. budgetId={} userId={} month={} lineCount={}",
                saved.budgetId(), userId, newMonth, templateLines.size());
        return enrichWithSpending(saved, userId, newMonth);
    }

    /**
     * REQ-5.1 "Template Inheritance": explicit payload lines always win; the template (an
     * existing budget of the same user) only seeds line items when no lines are supplied; with
     * neither, the budget starts from scratch with zero lines.
     */
    private List<BudgetLine> resolveEffectiveLines(UUID userId, UUID templateId, List<BudgetLine> lines) {
        if (lines != null && !lines.isEmpty()) {
            return lines.stream()
                    .map(l -> new BudgetLine(l.lineId(), l.budgetId(), l.category().strip(),
                            l.limitAmount(), l.description(), l.spentAmount()))
                    .toList();
        }
        if (templateId == null) {
            return List.of();
        }
        var template = budgetRepository.findById(templateId)
                .filter(b -> b.userId().equals(userId))
                .orElseThrow(() -> new InvalidBudgetException(
                        "Template budget %s was not found.".formatted(templateId)));
        var templateLines = cloneLines(template.lines());
        if (templateLines.size() > LineItemLimitExceededException.MAX_LINE_ITEMS) {
            throw new LineItemLimitExceededException(templateLines.size());
        }
        return templateLines;
    }

    /** Detached copies of persisted lines (no IDs, zero spend) suitable for seeding a new budget. */
    private List<BudgetLine> cloneLines(List<BudgetLine> lines) {
        return lines.stream()
                .map(l -> new BudgetLine(null, null, l.category(), l.limitAmount(),
                        l.description(), BigDecimal.ZERO))
                .toList();
    }

    /**
     * REQ-5.1 "Modification Guard" / "Immutability upon Closure": every write against a CLOSED
     * budget is rejected with 422; the budget must be explicitly reopened first.
     */
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

    private void requireInputs(UUID userId, LocalDate month) {
        if (userId == null) {
            throw new InvalidBudgetException("userId is required.");
        }
        if (month == null) {
            throw new InvalidBudgetException("month is required.");
        }
    }

    /**
     * REQ-5.1 Constraints, enforced before any persistence: blank categories, duplicate
     * categories (case-insensitive), the [0.00, 999999999.99] limitAmount range, the 2-decimal
     * scale rule (never silently rounded), and the 50-line ceiling.
     */
    private void validateLines(List<BudgetLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        if (lines.size() > LineItemLimitExceededException.MAX_LINE_ITEMS) {
            throw new LineItemLimitExceededException(lines.size());
        }

        Set<String> seenCategories = new HashSet<>();
        for (BudgetLine line : lines) {
            if (line == null) {
                throw new InvalidBudgetException("Budget lines must not contain null entries.");
            }
            String category = line.category();
            if (category == null || category.isBlank()) {
                throw new InvalidBudgetException("Every budget line requires a non-blank category.");
            }
            if (!seenCategories.add(category.strip().toLowerCase(Locale.ROOT))) {
                throw new InvalidBudgetException(
                        "Duplicate category '%s' in budget payload.".formatted(category.strip()));
            }
            validateLimitAmount(category.strip(), line.limitAmount());
        }
    }

    private void validateLimitAmount(String category, BigDecimal limitAmount) {
        if (limitAmount == null) {
            throw new InvalidBudgetException(
                    "Category '%s' requires a limitAmount.".formatted(category));
        }
        // Deliberately the literal scale of the payload value, NOT stripTrailingZeros(): a client
        // sending "100.000" wrote 3 decimal places and must be rejected, even though the trailing
        // zeros are numerically insignificant (stripping would also collapse "100.000" to 1E+2,
        // scale -2, masking the violation entirely). Amounts are never silently rounded.
        if (limitAmount.scale() > MAX_MONETARY_SCALE) {
            throw new InvalidBudgetException(
                    "Category '%s' has limitAmount %s with more than 2 decimal places; amounts are not rounded."
                            .formatted(category, limitAmount.toPlainString()));
        }
        if (limitAmount.compareTo(MIN_LIMIT_AMOUNT) < 0 || limitAmount.compareTo(MAX_LIMIT_AMOUNT) > 0) {
            throw new InvalidBudgetException(
                    "Category '%s' has limitAmount %s outside the allowed range [%s, %s]."
                            .formatted(category, limitAmount.toPlainString(),
                                    MIN_LIMIT_AMOUNT.toPlainString(), MAX_LIMIT_AMOUNT.toPlainString()));
        }
    }

    /**
     * REQ-5.1 "Spend Amount Initialization": future periods report $0.00 per line; current and
     * past periods sum the user's approved transactions per category across the month.
     */
    private Budget enrichWithSpending(Budget budget, UUID userId, LocalDate month) {
        boolean futurePeriod = month.isAfter(currentMonth());
        LocalDate monthEnd = month.plusMonths(1).minusDays(1);

        List<BudgetLine> enrichedLines = budget.lines().stream()
                .map(line -> {
                    BigDecimal spent = futurePeriod
                            ? BigDecimal.ZERO
                            : transactionService.sumMonthlyExpensesPerCategory(
                                    userId, month, monthEnd, line.category());
                    return new BudgetLine(line.lineId(), line.budgetId(), line.category(),
                            line.limitAmount(), line.description(), spent);
                })
                .toList();

        return new Budget(budget.budgetId(), budget.userId(), budget.effectiveMonth(),
                budget.version(), budget.status(), budget.description(), enrichedLines, budget.createdAt());
    }
}
