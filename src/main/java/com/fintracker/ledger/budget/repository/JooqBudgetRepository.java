package com.fintracker.ledger.budget.repository;

import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.model.BudgetStatus;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.jooq.impl.DSL.*;

/** Outbound Adapter: jOOQ implementation of {@link BudgetRepository}. */
@Repository
public class JooqBudgetRepository implements BudgetRepository {

    private static final String SCHEMA = "ledger";
    private static final String BUDGETS = "budgets";
    private static final String BUDGET_LINES = "budget_lines";

    private final DSLContext dsl;

    public JooqBudgetRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Budget> findById(UUID budgetId) {
        return fetchOne(field("budget_id").eq(budgetId));
    }

    @Override
    public Optional<Budget> findByUserAndMonth(UUID userId, LocalDate effectiveMonth) {
        return fetchOne(field("user_id").eq(userId).and(field("effective_month").eq(effectiveMonth)));
    }

    @Override
    public Optional<Budget> findLatestByUserId(UUID userId) {
        return fetchLatest(field("user_id").eq(userId));
    }

    @Override
    public Optional<Budget> findLatestActiveByUserId(UUID userId) {
        return fetchLatest(field("user_id").eq(userId)
                .and(field("status").eq(BudgetStatus.ACTIVE.name())));
    }

    @Override
    public Budget save(Budget budget) {
        var id = UUID.randomUUID();
        var status = budget.status() != null ? budget.status() : BudgetStatus.ACTIVE;
        // Budget header and lines are persisted atomically: a failure while inserting lines must
        // not leave a half-written budget behind.
        dsl.transaction(cfg -> {
            var tx = cfg.dsl();
            tx.insertInto(table(name(SCHEMA, BUDGETS)))
                    .set(field("budget_id"), id)
                    .set(field("user_id"), budget.userId())
                    .set(field("effective_month"), budget.effectiveMonth())
                    .set(field("version"), budget.version())
                    .set(field("status"), status.name())
                    .set(field("description"), budget.description())
                    .execute();

            if (budget.lines() != null) {
                saveLines(tx, id, budget.lines());
            }
        });
        return findById(id).orElseThrow();
    }

    @Override
    public void updateLines(UUID budgetId, List<BudgetLine> lines) {
        // Delete + reinsert + version bump must be atomic: a failure mid-rewrite would otherwise
        // silently drop the budget's existing lines.
        dsl.transaction(cfg -> {
            var tx = cfg.dsl();
            tx.deleteFrom(table(name(SCHEMA, BUDGET_LINES)))
                    .where(field("budget_id").eq(budgetId))
                    .execute();
            saveLines(tx, budgetId, lines);
            tx.update(table(name(SCHEMA, BUDGETS)))
                    .set(field("version", Integer.class), field("version", Integer.class).plus(1))
                    .where(field("budget_id").eq(budgetId))
                    .execute();
        });
    }

    @Override
    public void updateStatus(UUID budgetId, BudgetStatus status) {
        dsl.update(table(name(SCHEMA, BUDGETS)))
                .set(field("status"), status.name())
                .where(field("budget_id").eq(budgetId))
                .execute();
    }

    @Override
    public int closeAllBefore(LocalDate cutoffDate) {
        // System-wide batch: the tenant-isolation RLS policy would hide every row from a session
        // with no user context, so the budgets_system_batch_select / budgets_system_batch_update
        // policies (V8) are activated via the app.system_job flag. SET LOCAL restricts the
        // variable's lifetime to this transaction block — Postgres automatically discards it
        // (or restores the previous value) the moment the transaction ends, so the elevation
        // never leaks to pooled request connections.
        return dsl.transactionResult(cfg -> {
            var txDsl = cfg.dsl();
            txDsl.execute("SET LOCAL app.system_job = 'true'");
            return txDsl.update(table(name(SCHEMA, BUDGETS)))
                    .set(field("status"), BudgetStatus.CLOSED.name())
                    .where(field("status").eq(BudgetStatus.ACTIVE.name()))
                    .and(field("effective_month").lessThan(cutoffDate))
                    .execute();
        });
    }

    private Optional<Budget> fetchOne(Condition condition) {
        return dsl.selectFrom(table(name(SCHEMA, BUDGETS)))
                .where(condition)
                .fetchOptional(this::mapBudget);
    }

    private Optional<Budget> fetchLatest(Condition condition) {
        return dsl.selectFrom(table(name(SCHEMA, BUDGETS)))
                .where(condition)
                .orderBy(field("effective_month").desc())
                .limit(1)
                .fetchOptional(this::mapBudget);
    }

    private Budget mapBudget(Record r) {
        var budgetId = r.get("budget_id", UUID.class);
        return new Budget(budgetId, r.get("user_id", UUID.class),
                r.get("effective_month", LocalDate.class),
                r.get("version", Integer.class),
                BudgetStatus.valueOf(r.get("status", String.class)),
                r.get("description", String.class),
                fetchLines(budgetId),
                r.get("created_at", OffsetDateTime.class));
    }

    private void saveLines(DSLContext ctx, UUID budgetId, List<BudgetLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        // Single batched round-trip instead of one INSERT per line (a budget may carry up to 50).
        ctx.batch(lines.stream()
                        .map(line -> ctx.insertInto(table(name(SCHEMA, BUDGET_LINES)))
                                .set(field("line_id"), UUID.randomUUID())
                                .set(field("budget_id"), budgetId)
                                .set(field("category"), line.category())
                                .set(field("limit_amount"), line.limitAmount())
                                .set(field("description"), line.description()))
                        .toList())
                .execute();
    }

    private List<BudgetLine> fetchLines(UUID budgetId) {
        return dsl.selectFrom(table(name(SCHEMA, BUDGET_LINES)))
                .where(field("budget_id").eq(budgetId))
                .fetch(r -> new BudgetLine(
                        r.get("line_id", UUID.class),
                        r.get("budget_id", UUID.class),
                        r.get("category", String.class),
                        r.get("limit_amount", BigDecimal.class),
                        r.get("description", String.class),
                        BigDecimal.ZERO
                ));
    }

    // ------------------------------------------------------- REQ-5.2 line-item operations

    @Override
    public Optional<BudgetLine> findLineById(UUID budgetId, UUID lineId) {
        return dsl.selectFrom(table(name(SCHEMA, BUDGET_LINES)))
                .where(field("line_id").eq(lineId).and(field("budget_id").eq(budgetId)))
                .fetchOptional(r -> new BudgetLine(
                        r.get("line_id", UUID.class),
                        r.get("budget_id", UUID.class),
                        r.get("category", String.class),
                        r.get("limit_amount", BigDecimal.class),
                        r.get("description", String.class),
                        BigDecimal.ZERO
                ));
    }

    @Override
    public int countLines(UUID budgetId) {
        return dsl.selectCount()
                .from(table(name(SCHEMA, BUDGET_LINES)))
                .where(field("budget_id").eq(budgetId))
                .fetchOne(0, int.class);
    }

    @Override
    public boolean existsCategoryIgnoreCase(UUID budgetId, String category, UUID excludeLineId) {
        Condition condition = field("budget_id").eq(budgetId)
                .and(lower(field("category", String.class)).eq(category.toLowerCase(Locale.ROOT)));
        if (excludeLineId != null) {
            condition = condition.and(field("line_id").ne(excludeLineId));
        }
        return dsl.fetchExists(dsl.selectOne()
                .from(table(name(SCHEMA, BUDGET_LINES)))
                .where(condition));
    }

    @Override
    public BudgetLine insertLine(UUID budgetId, BudgetLine line) {
        var lineId = UUID.randomUUID();
        // Insert + version bump must be atomic: a failure after the insert would otherwise leave
        // the budget's version stale relative to its actual line contents.
        dsl.transaction(cfg -> {
            var tx = cfg.dsl();
            tx.insertInto(table(name(SCHEMA, BUDGET_LINES)))
                    .set(field("line_id"), lineId)
                    .set(field("budget_id"), budgetId)
                    .set(field("category"), line.category())
                    .set(field("limit_amount"), line.limitAmount())
                    .set(field("description"), line.description())
                    .execute();
            bumpVersion(tx, budgetId);
        });
        return new BudgetLine(lineId, budgetId, line.category(), line.limitAmount(),
                line.description(), BigDecimal.ZERO);
    }

    @Override
    public void updateLineLimit(UUID lineId, BigDecimal newLimitAmount) {
        dsl.transaction(cfg -> {
            var tx = cfg.dsl();
            var budgetId = tx.select(field("budget_id", UUID.class))
                    .from(table(name(SCHEMA, BUDGET_LINES)))
                    .where(field("line_id").eq(lineId))
                    .fetchOne(field("budget_id", UUID.class));
            tx.update(table(name(SCHEMA, BUDGET_LINES)))
                    .set(field("limit_amount"), newLimitAmount)
                    .where(field("line_id").eq(lineId))
                    .execute();
            if (budgetId != null) {
                bumpVersion(tx, budgetId);
            }
        });
    }

    @Override
    public void deleteLine(UUID lineId) {
        dsl.transaction(cfg -> {
            var tx = cfg.dsl();
            var budgetId = tx.select(field("budget_id", UUID.class))
                    .from(table(name(SCHEMA, BUDGET_LINES)))
                    .where(field("line_id").eq(lineId))
                    .fetchOne(field("budget_id", UUID.class));
            tx.deleteFrom(table(name(SCHEMA, BUDGET_LINES)))
                    .where(field("line_id").eq(lineId))
                    .execute();
            if (budgetId != null) {
                bumpVersion(tx, budgetId);
            }
        });
    }

    private void bumpVersion(DSLContext ctx, UUID budgetId) {
        ctx.update(table(name(SCHEMA, BUDGETS)))
                .set(field("version", Integer.class), field("version", Integer.class).plus(1))
                .where(field("budget_id").eq(budgetId))
                .execute();
    }
}
