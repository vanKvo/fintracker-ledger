package com.fintracker.ledger.category.repository;

import com.fintracker.ledger.category.model.Category;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

@Repository
public class JooqCategoryReadRepository implements CategoryReadRepository {

    private static final String SCHEMA = "ledger";
    private static final String TABLE = "categories";

    private final DSLContext dsl;

    public JooqCategoryReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<Category> findAllSystem() {
        return dsl.select()
                .from(name(SCHEMA, TABLE))
                .where(field(name(SCHEMA, TABLE, "level")).eq("SYSTEM"))
                .orderBy(field(name(SCHEMA, TABLE, "code")))
                .fetch(this::map);
    }

    @Override
    public List<Category> findAllUserCategories(UUID userId) {
        // RLS already limits USER rows to the session's user; the explicit filter keeps the
        // query correct on its own.
        return dsl.select()
                .from(name(SCHEMA, TABLE))
                .where(field(name(SCHEMA, TABLE, "level")).eq("USER"))
                .and(field(name(SCHEMA, TABLE, "user_id")).eq(userId))
                .orderBy(field(name(SCHEMA, TABLE, "category_name")))
                .fetch(this::map);
    }

    private Category map(org.jooq.Record r) {
        return new Category(
                r.get(field(name(SCHEMA, TABLE, "category_id")), UUID.class),
                r.get(field(name(SCHEMA, TABLE, "category_name")), String.class),
                Category.Level.valueOf(r.get(field(name(SCHEMA, TABLE, "level")), String.class)),
                r.get(field(name(SCHEMA, TABLE, "user_id")), UUID.class),
                r.get(field(name(SCHEMA, TABLE, "code")), String.class),
                Boolean.TRUE.equals(r.get(field(name(SCHEMA, TABLE, "is_active")), Boolean.class)));
    }
}
