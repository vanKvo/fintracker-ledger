package com.fintracker.ledger.budget.repository;

import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.model.BudgetStatus;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.jooq.impl.DSL.*;

/** Outbound Adapter: jOOQ implementation of {@link BudgetRepository}. */
@Repository
public class JooqBudgetRepository implements BudgetRepository {

    private final DSLContext dsl;

    public JooqBudgetRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Budget> findByUserAndMonth(UUID userId, LocalDate effectiveMonth) {
        return dsl.selectFrom(table(name("ledger", "budgets")))
                .where(field("user_id").eq(userId))
                .and(field("effective_month").eq(effectiveMonth))
                .fetchOptional(r -> {
                    var budgetId = r.get("budget_id", UUID.class);
                    return new Budget(budgetId, r.get("user_id", UUID.class),
                            r.get("effective_month", LocalDate.class),
                            r.get("version", Integer.class),
                            // STUB: ledger.budgets has no status column yet (REQ-5.1 Data Impacts
                            // requires the migration). Hardcoded so the module compiles.
                            BudgetStatus.ACTIVE,
                            r.get("description", String.class),
                            fetchLines(budgetId),
                            r.get("created_at", OffsetDateTime.class));
                });
    }

    @Override
    public Optional<Budget> findLatestByUserId(UUID userId) {
        return dsl.selectFrom(table(name("ledger", "budgets")))
                .where(field("user_id").eq(userId))
                .orderBy(field("effective_month").desc())
                .limit(1)
                .fetchOptional(r -> {
                    var budgetId = r.get("budget_id", UUID.class);
                    return new Budget(budgetId, r.get("user_id", UUID.class),
                            r.get("effective_month", LocalDate.class),
                            r.get("version", Integer.class),
                            // STUB: ledger.budgets has no status column yet (REQ-5.1 Data Impacts
                            // requires the migration). Hardcoded so the module compiles.
                            BudgetStatus.ACTIVE,
                            r.get("description", String.class),
                            fetchLines(budgetId),
                            r.get("created_at", OffsetDateTime.class));
                });
    }

    @Override
    public Budget save(Budget budget) {
        var id = UUID.randomUUID();
        dsl.insertInto(table(name("ledger", "budgets")))
                .set(field("budget_id"), id)
                .set(field("user_id"), budget.userId())
                .set(field("effective_month"), budget.effectiveMonth())
                .set(field("version"), budget.version())
                .set(field("description"), budget.description())
                .execute();

        if (budget.lines() != null) {
            saveLines(id, budget.lines());
        }
        return findByUserAndMonth(budget.userId(), budget.effectiveMonth()).orElseThrow();
    }

    @Override
    public void updateLines(UUID budgetId, List<BudgetLine> lines) {
        dsl.deleteFrom(table(name("ledger", "budget_lines")))
                .where(field("budget_id").eq(budgetId))
                .execute();
        saveLines(budgetId, lines);
    }

    private void saveLines(UUID budgetId, List<BudgetLine> lines) {
        lines.forEach(line ->
                dsl.insertInto(table(name("ledger", "budget_lines")))
                        .set(field("line_id"), UUID.randomUUID())
                        .set(field("budget_id"), budgetId)
                        .set(field("category"), line.category())
                        .set(field("limit_amount"), line.limitAmount())
                        .set(field("description"), line.description())
                        .execute());
    }

    private List<BudgetLine> fetchLines(UUID budgetId) {
        return dsl.selectFrom(table(name("ledger", "budget_lines")))
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
}
