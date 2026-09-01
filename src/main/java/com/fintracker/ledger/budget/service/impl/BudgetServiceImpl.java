package com.fintracker.ledger.budget.service.impl;

import com.fintracker.ledger.budget.exception.HistoricalBudgetException;
import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import com.fintracker.ledger.budget.exception.LineItemLimitExceededException;
import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.model.BudgetStatus;
import com.fintracker.ledger.budget.repository.BudgetRepository;
import com.fintracker.ledger.budget.service.BudgetService;
import com.fintracker.ledger.budget.validation.BudgetMonetaryPolicy;
import com.fintracker.ledger.shared.exception.ResourceNotFoundException;
import com.fintracker.ledger.transaction.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class BudgetServiceImpl implements BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetServiceImpl.class);

    /**
     * REQ-5.1 A.2 year bounds. The floor is the epoch year — no financial history predates this
     * product — and the ceiling is the widest value a four-digit year can express. Their purpose
     * is to turn a nonsense year into an explicit 400 rather than an expensive scan that returns
     * an empty list and looks like success.
     */
    private static final int MIN_YEAR = 1970;
    private static final int MAX_YEAR = 9999;

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

    /**
     * REQ-5.1 A.2 "Get Budgets".
     *
     * <p>Deliberately routed through {@code findByUserAndMonthRange} rather than looping
     * {@link #getBudgetForMonth} over twelve months: that method lazily creates a budget for a
     * month that has none, so the loop would silently materialize up to twelve budgets every time
     * a user opened the page. A.2 "Pure Read" exists to forbid exactly that.
     *
     * <p>Cost is three queries — budgets, their lines, and one spend aggregate — regardless of how
     * many budgets or line items the year holds.
     */
    @Override
    public List<Budget> getBudgetsForYear(UUID userId, int year) {
        if (userId == null) {
            throw new InvalidBudgetException("userId is required.");
        }
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new InvalidBudgetException(
                    "year must be between %d and %d, but was %d.".formatted(MIN_YEAR, MAX_YEAR, year));
        }

        LocalDate january = LocalDate.of(year, Month.JANUARY, 1);
        LocalDate december = LocalDate.of(year, Month.DECEMBER, 1);

        var budgets = budgetRepository.findByUserAndMonthRange(userId, january, december);
        if (budgets.isEmpty()) {
            log.debug("No budgets found. userId={} year={}", userId, year);
            return List.of();
        }

        var spendByMonth = transactionService.sumExpensesByMonthAndCategory(
                userId, january, december.plusMonths(1).minusDays(1));

        log.info("Listed budgets for year. userId={} year={} budgetCount={}", userId, year, budgets.size());
        return budgets.stream().map(b -> enrichFromIndex(b, spendByMonth)).toList();
    }

    @Override
    public Budget getBudgetById(UUID userId, UUID budgetId) {
        var budget = findOwnedBudget(userId, budgetId);
        return enrichWithSpending(budget, userId, budget.effectiveMonth());
    }

    @Override
    public List<Integer> getBudgetYears(UUID userId) {
        if (userId == null) {
            throw new InvalidBudgetException("userId is required.");
        }
        return budgetRepository.findBudgetYears(userId);
    }

    /**
     * REQ-5.1 A.3 "Delete Budget".
     *
     * <p>The guard is applied twice on purpose. The read below exists to tell the caller
     * which rule it broke (404 vs 422); the authoritative check is the ACTIVE + ownership
     * predicate inside the DELETE itself, which cannot be raced. Deciding solely on the read would
     * leave a window in which a budget closed by the month-end scheduler between the check and the
     * write still gets deleted — a closed accounting period vanishing is precisely what
     * "Immutability upon Closure" is meant to prevent.
     */
    @Override
    public void deleteBudget(UUID userId, UUID budgetId) {
        var budget = findOwnedBudget(userId, budgetId);
        rejectIfClosed(budget);

        int deleted = budgetRepository.deleteByIdIfActive(budgetId, userId);
        if (deleted == 0) {
            // The guard passed, but another thread changed the budget before the DELETE.
            // Check the current state in the database to (e.g., already closed vs. deleted).
            log.warn("Delete matched no rows after passing the guard; resolving the race. budgetId={} userId={}",
                    budgetId, userId);
            budgetRepository.findById(budgetId)
                    .filter(b -> b.userId().equals(userId))
                    .ifPresent(this::rejectIfClosed);
            throw new ResourceNotFoundException("Budget", budgetId);
        }

        // Deleting a period is permanent and user-driven. 
        // Log at INFO with full context so the deleted data can be reconstructed if needed.
        log.info("Deleted budget. budgetId={} userId={} month={} lineCount={}",
                budgetId, userId, budget.effectiveMonth(), budget.lines().size());
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
        try {
            var saved = budgetRepository.save(newBudget);
            log.info("Created new budget. budgetId={} userId={} month={} lineCount={}",
                    saved.budgetId(), userId, normalizedMonth, effectiveLines.size());
            return enrichWithSpending(saved, userId, normalizedMonth);
        } catch (DataIntegrityViolationException raceLoss) {
            // Another request (e.g. a concurrent lazy-create via GET) created this month's budget
            // first — the unique (user_id, effective_month) constraint rejected our insert. REQ-5.1
            // treats this the same as if we had seen it during the read above: fall back to update.
            log.info("Lost the create race for month={} userId={}; applying payload as an update.",
                    normalizedMonth, userId);
            var budget = budgetRepository.findByUserAndMonth(userId, normalizedMonth)
                    .orElseThrow(() -> raceLoss);
            rejectIfClosed(budget);
            budgetRepository.updateLines(budget.budgetId(), effectiveLines);
            return enrichWithSpending(
                    budgetRepository.findById(budget.budgetId()).orElseThrow(
                            () -> new ResourceNotFoundException("Budget", budget.budgetId())),
                    userId, normalizedMonth);
        }
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

        try {
            var saved = budgetRepository.save(
                    new Budget(null, userId, newMonth, 1, lazyCreateStatusFor(newMonth), null,
                            templateLines, null));
            log.info("Lazily created budget. budgetId={} userId={} month={} lineCount={}",
                    saved.budgetId(), userId, newMonth, templateLines.size());
            return enrichWithSpending(saved, userId, newMonth);
        } catch (DataIntegrityViolationException raceLoss) {
            // Another concurrent request (e.g. an explicit PUT) created this month's budget first.
            // A read should never overwrite that — just return what won the race.
            log.info("Lost the lazy-create race for month={} userId={}; returning the existing budget.",
                    newMonth, userId);
            return budgetRepository.findByUserAndMonth(userId, newMonth)
                    .map(b -> enrichWithSpending(b, userId, newMonth))
                    .orElseThrow(() -> raceLoss);
        }
    }

    /**
     * The status a <em>lazily</em> created budget is born in — the one materialized by a read of a
     * month that has no budget yet, which no user explicitly asked for.
     *
     * <p>REQ-5.1 "State Initialization" pins <em>explicitly</em> created budgets (upsertBudget) to
     * ACTIVE for any period, so a user deliberately backfilling a past month can still edit it.
     * A system-materialized row has no such intent behind it, and stamping it ACTIVE contradicts
     * REQ-5.1 "Automated Period Closure": the closure job only runs on the 1st of the month, so a
     * row auto-created for an elapsed month would advertise itself as ACTIVE — and render an
     * ACTIVE badge in the UI — until the next month boundary. Creating it in the state the closure
     * job would already have left it in keeps read-only browsing of history free of side effects
     * the user can observe.
     */
    private BudgetStatus lazyCreateStatusFor(LocalDate normalizedMonth) {
        return normalizedMonth.isBefore(currentMonth()) ? BudgetStatus.CLOSED : BudgetStatus.ACTIVE;
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
        BudgetMonetaryPolicy.validate(category, limitAmount);
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

    /**
     * The batched counterpart of {@link #enrichWithSpending}, reading from a pre-computed
     * month → category → total index instead of querying per line.
     *
     * <p>Applies the identical REQ-5.1 "Spend Amount Initialization" rules: future periods report
     * $0.00, and category matching is case-insensitive. A category absent from the index has no
     * approved spending and reports {@code 0.00} — never null, so a line without transactions is
     * indistinguishable from one whose transactions summed to zero, exactly as in the single-month
     * read path.
     */
    private Budget enrichFromIndex(Budget budget, Map<LocalDate, Map<String, BigDecimal>> spendByMonth) {
        boolean futurePeriod = budget.effectiveMonth().isAfter(currentMonth());
        Map<String, BigDecimal> monthSpend = futurePeriod
                ? Map.of()
                : spendByMonth.getOrDefault(budget.effectiveMonth(), Map.of());

        List<BudgetLine> enrichedLines = budget.lines().stream()
                .map(line -> new BudgetLine(line.lineId(), line.budgetId(), line.category(),
                        line.limitAmount(), line.description(),
                        monthSpend.getOrDefault(line.category().toLowerCase(Locale.ROOT), BigDecimal.ZERO)))
                .toList();

        return new Budget(budget.budgetId(), budget.userId(), budget.effectiveMonth(),
                budget.version(), budget.status(), budget.description(), enrichedLines, budget.createdAt());
    }
}
