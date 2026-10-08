package com.fintracker.ledger.category.repository;

import com.fintracker.ledger.category.model.Category;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

@Repository
public class JooqCategoryRepository implements CategoryRepository {

    private static final String SCHEMA = "ledger";
    private static final String TABLE = "categories";

    private final DSLContext dsl;

    public JooqCategoryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    private Category map(org.jooq.Record r) {
        var levelStr = r.get(field(name(SCHEMA, TABLE, "level")), String.class);
        var userId = r.get(field(name(SCHEMA, TABLE, "user_id")), UUID.class);
        return new Category(
                r.get(field(name(SCHEMA, TABLE, "category_id")), UUID.class),
                r.get(field(name(SCHEMA, TABLE, "category_name")), String.class),
                Category.Level.valueOf(levelStr),
                userId,
                r.get(field(name(SCHEMA, TABLE, "code")), String.class),
                Boolean.TRUE.equals(r.get(field(name(SCHEMA, TABLE, "is_active")), Boolean.class)));
    }

    @Override
    public Optional<Category> findByIdAndAccessibleToUser(UUID categoryId, UUID userId) {
        return dsl.select()
                .from(name(SCHEMA, TABLE))
                .where(field(name(SCHEMA, TABLE, "category_id")).eq(categoryId))
                .and(field(name(SCHEMA, TABLE, "level")).eq("SYSTEM")
                        .or(field(name(SCHEMA, TABLE, "user_id")).eq(userId)))
                .fetchOptional(this::map);
    }

    @Override
    public List<Category> findAllAccessibleToUser(UUID userId) {
        return dsl.select()
                .from(name(SCHEMA, TABLE))
                .where(field(name(SCHEMA, TABLE, "level")).eq("SYSTEM")
                        .or(field(name(SCHEMA, TABLE, "user_id")).eq(userId)))
                .fetch(this::map);
    }

    @Override
    public boolean existsByNormalizedNameAccessibleToUser(String normalizedName, UUID userId) {
        return dsl.fetchExists(dsl.select()
                .from(name(SCHEMA, TABLE))
                .where(field(name(SCHEMA, TABLE, "category_name")).eq(normalizedName))
                .and(field(name(SCHEMA, TABLE, "level")).eq("SYSTEM")
                        .or(field(name(SCHEMA, TABLE, "user_id")).eq(userId))));
    }

    @Override
    public long countByUserId(UUID userId) {
        return dsl.selectCount()
                .from(name(SCHEMA, TABLE))
                .where(field(name(SCHEMA, TABLE, "user_id")).eq(userId))
                .fetchOne(0, Long.class);
    }

    @Override
    public Category insert(UUID categoryId, String normalizedName, UUID userId) {
        dsl.insertInto(org.jooq.impl.DSL.table(name(SCHEMA, TABLE)))
                .columns(field(name("category_id")), field(name("category_name")),
                        field(name("level")), field(name("user_id")))
                .values(categoryId, normalizedName, "USER", userId)
                .execute();
        return new Category(categoryId, normalizedName, Category.Level.USER, userId);
    }

    @Override
    public Category update(UUID categoryId, String normalizedName) {
        dsl.update(org.jooq.impl.DSL.table(name(SCHEMA, TABLE)))
                .set(field(name("category_name"), String.class), normalizedName)
                .where(field(name("category_id")).eq(categoryId))
                .execute();
        return findByIdIgnoringAccess(categoryId);
    }

    @Override
    public void delete(UUID categoryId) {
        dsl.deleteFrom(org.jooq.impl.DSL.table(name(SCHEMA, TABLE)))
                .where(field(name("category_id")).eq(categoryId))
                .execute();
    }

    private Category findByIdIgnoringAccess(UUID categoryId) {
        return dsl.select()
                .from(name(SCHEMA, TABLE))
                .where(field(name(SCHEMA, TABLE, "category_id")).eq(categoryId))
                .fetchOptional(this::map)
                .orElseThrow(() -> new IllegalStateException(
                        "Category %s vanished immediately after being updated.".formatted(categoryId)));
    }
}
