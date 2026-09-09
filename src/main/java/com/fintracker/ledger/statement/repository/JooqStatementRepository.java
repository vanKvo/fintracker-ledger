package com.fintracker.ledger.statement.repository;

import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.transaction.model.Transaction;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
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

    // Every non-key column selected below must also appear in the GROUP BY, since the three
    // COUNT(...) FILTER(...) aggregates require one. statement_month reads back like any
    // other column even though V16 made it a generated column — only writes to it are
    // disallowed, which is why insert() below no longer names it.
    private static final List<org.jooq.Field<?>> STATEMENT_COLUMNS = List.of(
            field(name(SCHEMA, TABLE, "statement_id")),
            field(name(SCHEMA, TABLE, "account_id")),
            field(name(SCHEMA, TABLE, "s3_object_key")),
            field(name(SCHEMA, TABLE, "statement_month")),
            field(name(SCHEMA, TABLE, "status")),
            field(name(SCHEMA, TABLE, "description")),
            field(name(SCHEMA, TABLE, "upload_date")),
            field(name(SCHEMA, TABLE, "source_format")),
            field(name(SCHEMA, TABLE, "bank_id")),
            field(name(SCHEMA, TABLE, "content_hash")),
            field(name(SCHEMA, TABLE, "opening_date")),
            field(name(SCHEMA, TABLE, "closing_date"))
    );

    @Override
    public List<Statement> findAllByUserId(UUID userId) {
        return selectWithCounts(field(name(SCHEMA, "accounts", "user_id")).eq(userId));
    }

    @Override
    public Optional<Statement> findByAccountIdAndContentHash(UUID accountId, String contentHash) {
        return selectWithCounts(
                        field(name(SCHEMA, TABLE, "account_id")).eq(accountId)
                                .and(field(name(SCHEMA, TABLE, "content_hash")).eq(contentHash)))
                .stream().findFirst();
    }

    @Override
    public Optional<Statement> findByAccountIdAndStatementMonth(UUID accountId, LocalDate statementMonth) {
        return selectWithCounts(
                        field(name(SCHEMA, TABLE, "account_id")).eq(accountId)
                                .and(field(name(SCHEMA, TABLE, "statement_month")).eq(statementMonth)))
                .stream().findFirst();
    }

    /**
     * Statement rows with live transaction counts, matching the given predicate. Shared
     * by every read that must report real counts — the duplicate-check finders need the
     * existing statement's transaction count for the 409 response (REQ-STMT-03/06), so a
     * plain single-table lookup returning zeroed counts would not do.
     */
    private List<Statement> selectWithCounts(Condition condition) {
        var txId = field(name(SCHEMA, "transactions", "transaction_id"));
        var txStatus = field(name(SCHEMA, "transactions", "status"), String.class);

        var txCount = count(txId)
                .filterWhere(txStatus.ne(Transaction.TransactionStatus.DELETED.name()))
                .as("tx_count");
        var pendingCount = count(txId)
                .filterWhere(txStatus.eq(Transaction.TransactionStatus.PENDING.name()))
                .as("pending_count");
        var approvedCount = count(txId)
                .filterWhere(txStatus.eq(Transaction.TransactionStatus.POSTED.name()))
                .as("approved_count");

        var selectFields = new java.util.ArrayList<org.jooq.SelectFieldOrAsterisk>(STATEMENT_COLUMNS);
        selectFields.add(txCount);
        selectFields.add(pendingCount);
        selectFields.add(approvedCount);

        return dsl.select(selectFields)
                .from(table(name(SCHEMA, TABLE)))
                .join(table(name(SCHEMA, "accounts")))
                .on(field(name(SCHEMA, TABLE, "account_id"))
                        .eq(field(name(SCHEMA, "accounts", "account_id"))))
                .leftJoin(table(name(SCHEMA, "transactions")))
                .on(field(name(SCHEMA, "transactions", "statement_id"))
                        .eq(field(name(SCHEMA, TABLE, "statement_id"))))
                .where(condition)
                .groupBy(STATEMENT_COLUMNS)
                .orderBy(field(name(SCHEMA, TABLE, "upload_date")).desc())
                .fetch(this::mapToStatementWithCounts);
    }

    @Override
    public Optional<Statement> findByIdAndUserId(UUID statementId, UUID userId) {
        return dsl.selectFrom(table(name(SCHEMA, TABLE)))
                .where(field("statement_id").eq(statementId))
                .and(field("user_id").eq(userId))
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
    public void deleteByIdAndUserId(UUID statementId, UUID userId) {
        dsl.deleteFrom(table(name(SCHEMA, TABLE)))
                .where(field("statement_id").eq(statementId))
                .and(field("user_id").eq(userId))
                .execute();
    }

    @Override
    public Statement insert(UUID statementId, UUID accountId, String s3ObjectKey,
                             LocalDate openingDate, LocalDate closingDate, String contentHash,
                             String description, String sourceFormat, String bankId) {
        // statement_month is intentionally absent from the column list: V16 made it a
        // generated column derived from closing_date, and Postgres rejects writes to
        // generated columns.
        //
        // returningResult(...) with twelve concrete, typed fields — neither the no-arg
        // returning() nor returning(asterisk()). The table here is a plain-SQL
        // table(name(...)) with no jOOQ-generated metadata, so the table's declared
        // record type is EMPTY: a bare returning() renders no RETURNING clause at all
        // (the row is written, fetchOne sees an empty result, returns null), and an
        // asterisk has no catalog to expand against, so jOOQ builds the result into a
        // record whose row type is literally () and the first record.get fails.
        // returningResult builds the result row type from the arguments instead. All
        // twelve columns mapToStatement reads must be listed — including upload_date
        // (DB default) and statement_month (generated), which are absent from the
        // insert's own column list. The requireNonNull turns any future "no row read
        // back" regression into a loud failure at the source instead of an NPE
        // somewhere downstream.
        return Objects.requireNonNull(
                dsl.insertInto(table(name(SCHEMA, TABLE)))
                        .columns(
                                field(name("statement_id")), field(name("account_id")), field(name("s3_object_key")),
                                field(name("opening_date")), field(name("closing_date")), field(name("content_hash")),
                                field(name("status")), field(name("description")),
                                field(name("source_format")), field(name("bank_id"))
                        )
                        .values(statementId, accountId, s3ObjectKey, openingDate, closingDate, contentHash,
                                Statement.StatementStatus.PROCESSING.name(), description, sourceFormat, bankId)
                        .returningResult(
                                field(name("statement_id"), UUID.class),
                                field(name("account_id"), UUID.class),
                                field(name("s3_object_key"), String.class),
                                field(name("statement_month"), LocalDate.class),
                                field(name("status"), String.class),
                                field(name("description"), String.class),
                                field(name("upload_date"), OffsetDateTime.class),
                                field(name("source_format"), String.class),
                                field(name("bank_id"), String.class),
                                field(name("content_hash"), String.class),
                                field(name("opening_date"), LocalDate.class),
                                field(name("closing_date"), LocalDate.class))
                        .fetchOne(this::mapToStatement),
                "statement insert read back no row — the RETURNING clause is required");
    }

    // Used for insert()/findByIdAndUserId(), which query ledger.statements alone — a statement
    // looked up or just created this way has no transactions associated with it yet in either
    // caller (see StatementServiceImpl), so the counts are correctly 0, not merely omitted.
    private Statement mapToStatement(org.jooq.Record record) {
        return new Statement(
                record.get("statement_id", UUID.class),
                record.get("account_id", UUID.class),
                record.get("s3_object_key", String.class),
                record.get("statement_month", LocalDate.class),
                Statement.StatementStatus.valueOf(record.get("status", String.class)),
                record.get("description", String.class),
                record.get("upload_date", OffsetDateTime.class),
                record.get("source_format", String.class),
                record.get("bank_id", String.class),
                record.get("content_hash", String.class),
                record.get("opening_date", LocalDate.class),
                record.get("closing_date", LocalDate.class),
                0, 0, 0);
    }

    private Statement mapToStatementWithCounts(org.jooq.Record record) {
        return new Statement(
                record.get("statement_id", UUID.class),
                record.get("account_id", UUID.class),
                record.get("s3_object_key", String.class),
                record.get("statement_month", LocalDate.class),
                Statement.StatementStatus.valueOf(record.get("status", String.class)),
                record.get("description", String.class),
                record.get("upload_date", OffsetDateTime.class),
                record.get("source_format", String.class),
                record.get("bank_id", String.class),
                record.get("content_hash", String.class),
                record.get("opening_date", LocalDate.class),
                record.get("closing_date", LocalDate.class),
                record.get("tx_count", Integer.class),
                record.get("pending_count", Integer.class),
                record.get("approved_count", Integer.class));
    }
}
