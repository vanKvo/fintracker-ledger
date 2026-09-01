package com.fintracker.ledger.budget.repository;

import com.fintracker.ledger.budget.model.BudgetTemplate;
import com.fintracker.ledger.budget.model.BudgetTemplateLine;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.*;

/** Outbound Adapter: jOOQ implementation of {@link BudgetTemplateRepository}. */
@Repository
public class JooqBudgetTemplateRepository implements BudgetTemplateRepository {

    private static final String SCHEMA = "ledger";
    private static final String TEMPLATES = "budget_templates";
    private static final String TEMPLATE_LINES = "budget_template_lines";

    private final DSLContext dsl;

    public JooqBudgetTemplateRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** REQ-5.3: a template is visible when it is global (unowned) or owned by the caller. */
    private static Condition visibleTo(UUID userId) {
        return field("user_id").isNull().or(field("user_id").eq(userId));
    }

    @Override
    public List<BudgetTemplate> findAvailable(UUID userId) {
        var headers = dsl.selectFrom(table(name(SCHEMA, TEMPLATES)))
                .where(visibleTo(userId))
                // System templates first — they are the onboarding path for a user with none of
                // their own — then alphabetically within each group.
                .orderBy(field("is_system").desc(), lower(field("name", String.class)).asc())
                .fetch();

        if (headers.isEmpty()) {
            return List.of();
        }

        // One query for every line in the catalog rather than one per template.
        var linesByTemplate = fetchLinesFor(headers.map(r -> r.get("id", UUID.class)));

        return headers.map(r -> mapTemplate(r,
                linesByTemplate.getOrDefault(r.get("id", UUID.class), List.of())));
    }

    @Override
    public Optional<BudgetTemplate> findVisibleById(UUID userId, UUID templateId) {
        return dsl.selectFrom(table(name(SCHEMA, TEMPLATES)))
                .where(field("id").eq(templateId))
                .and(visibleTo(userId))
                .fetchOptional()
                .map(r -> mapTemplate(r, fetchLines(templateId)));
    }

    @Override
    public boolean existsByUserAndNameIgnoreCase(UUID userId, String name) {
        return dsl.fetchExists(dsl.selectOne()
                .from(table(name(SCHEMA, TEMPLATES)))
                .where(field("user_id").eq(userId))
                .and(lower(field("name", String.class)).eq(name.strip().toLowerCase(Locale.ROOT))));
    }

    @Override
    public BudgetTemplate saveCustom(BudgetTemplate template, List<BudgetTemplateLine> lines) {
        var id = UUID.randomUUID();

        // Header and lines are written in one transaction: a failure partway must not leave a
        // named template behind with no allocations, which would silently seed empty budgets.
        dsl.transaction(cfg -> {
            var tx = cfg.dsl();
            tx.insertInto(table(name(SCHEMA, TEMPLATES)))
                    .set(field("id"), id)
                    .set(field("user_id"), template.userId())
                    .set(field("name"), template.name())
                    .set(field("description"), template.description())
                    .set(field("is_system"), false)
                    .execute();

            if (lines != null && !lines.isEmpty()) {
                tx.batch(lines.stream()
                                .map(l -> tx.insertInto(table(name(SCHEMA, TEMPLATE_LINES)))
                                        .set(field("id"), UUID.randomUUID())
                                        .set(field("template_id"), id)
                                        .set(field("category_name"), l.categoryName())
                                        .set(field("default_limit"), l.defaultLimit()))
                                .toList())
                        .execute();
            }
        });

        return findVisibleById(template.userId(), id).orElseThrow();
    }

    // ------------------------------------------------------------------ mapping

    private BudgetTemplate mapTemplate(Record r, List<BudgetTemplateLine> lines) {
        return new BudgetTemplate(
                r.get("id", UUID.class),
                r.get("user_id", UUID.class),
                r.get("name", String.class),
                r.get("description", String.class),
                Boolean.TRUE.equals(r.get("is_system", Boolean.class)),
                lines,
                r.get("created_at", OffsetDateTime.class));
    }

    private List<BudgetTemplateLine> fetchLines(UUID templateId) {
        return dsl.selectFrom(table(name(SCHEMA, TEMPLATE_LINES)))
                .where(field("template_id").eq(templateId))
                .orderBy(lower(field("category_name", String.class)).asc())
                .fetch(JooqBudgetTemplateRepository::mapLine);
    }

    private Map<UUID, List<BudgetTemplateLine>> fetchLinesFor(List<UUID> templateIds) {
        return dsl.selectFrom(table(name(SCHEMA, TEMPLATE_LINES)))
                .where(field("template_id").in(templateIds))
                .orderBy(lower(field("category_name", String.class)).asc())
                .fetch(JooqBudgetTemplateRepository::mapLine)
                .stream()
                .collect(Collectors.groupingBy(BudgetTemplateLine::templateId));
    }

    private static BudgetTemplateLine mapLine(Record r) {
        return new BudgetTemplateLine(
                r.get("id", UUID.class),
                r.get("template_id", UUID.class),
                r.get("category_name", String.class),
                r.get("default_limit", BigDecimal.class));
    }
}
