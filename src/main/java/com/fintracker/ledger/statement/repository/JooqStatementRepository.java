package com.fintracker.ledger.statement.repository;

import com.fintracker.ledger.statement.model.Statement;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.jooq.impl.DSL.*;

@Repository
public class JooqStatementRepository implements StatementRepository {

    private static final String SCHEMA = "ledger";
    private static final String TABLE = "statements";

    private final DSLContext dsl;

    public JooqStatementRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<Statement> findAllByUserId(UUID userId) {
        return dsl.select()
                .from(table(name(SCHEMA, TABLE)))
                .join(table(name(SCHEMA, "accounts")))
                .on(field(name(SCHEMA, TABLE, "account_id"))
                        .eq(field(name(SCHEMA, "accounts", "account_id"))))
                .where(field(name(SCHEMA, "accounts", "user_id")).eq(userId))
                .orderBy(field(name(SCHEMA, TABLE, "upload_date")).desc())
                .fetch(this::mapToStatement);
    }

    @Override
    public Optional<Statement> findById(UUID statementId) {
        return dsl.selectFrom(table(name(SCHEMA, TABLE)))
                .where(field("statement_id").eq(statementId))
                .fetchOptional(this::mapToStatement);
    }

    @Override
    public void updateStatus(UUID statementId, Statement.StatementStatus status) {
        dsl.update(table(name(SCHEMA, TABLE)))
                .set(field("status"), status.name())
                .where(field("statement_id").eq(statementId))
                .execute();
    }

    @Override
    public void deleteById(UUID statementId) {
        dsl.deleteFrom(table(name(SCHEMA, TABLE)))
                .where(field("statement_id").eq(statementId))
                .execute();
    }

    private Statement mapToStatement(org.jooq.Record record) {
        return new Statement(
                record.get("statement_id", UUID.class),
                record.get("account_id", UUID.class),
                record.get("s3_object_key", String.class),
                record.get("statement_month", LocalDate.class),
                Statement.StatementStatus.valueOf(record.get("status", String.class)),
                record.get("description", String.class),
                record.get("upload_date", OffsetDateTime.class));
    }
}
