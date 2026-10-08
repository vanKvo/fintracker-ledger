package com.fintracker.ledger.statement.repository;

import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.model.StatementOwner;
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
            field(name(SCHEMA, TABLE, "content_fingerprint")),
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

    @Override
    public Optional<Statement> findByAccountIdAndContentFingerprint(UUID accountId, String contentFingerprint) {
        return selectWithCounts(
                        field(name(SCHEMA, TABLE, "account_id")).eq(accountId)
                                .and(field(name(SCHEMA, TABLE, "content_fingerprint")).eq(contentFingerprint)))
                .stream().findFirst();
    }

    @Override
    public boolean updateContentFingerprint(UUID statementId, UUID userId, String contentFingerprint) {
        // user_id is in the WHERE clause, not merely relied upon via RLS: the update must be a
        // no-op (0 rows) rather than an error when the statement belongs to someone else, so the
        // caller can turn that into a 404 without leaking whether the id exists at all.
        return dsl.update(table(name(SCHEMA, TABLE)))
                .set(field(name("content_fingerprint")), contentFingerprint)
                .where(field(name("statement_id")).eq(statementId))
                .and(field(name("user_id")).eq(userId))
                .execute() > 0;
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
    public Optional<StatementOwner> findOwnerByStatementId(UUID statementId) {
        // No userId in the WHERE clause — deliberately, see the interface's own doc comment.
        // account_id/user_id are both plain columns on this table already (findByIdAndUserId
        // above filters on user_id directly), so this needs no join to the accounts table.
        //
        // ledger.statements FORCE-enables RLS (V3), so statements_isolation's
        // `user_id = current_setting('app.current_user_id', true)::uuid` applies even here —
        // and can never pass, since by definition no user_id is known yet (that's the whole
        // point of this lookup). V20 adds a narrow, separately-gated permissive policy
        // (app.internal_owner_lookup) just for this one SELECT; set/reset it on this exact
        // connection around the query, the same pattern RlsExecuteListener uses for
        // app.current_user_id, rather than depending on the generic per-request identity.
        //
        // app.current_user_id is also pinned to the nil UUID: a pooled connection that already
        // served a user request has it RESET to '' (not unset), and statements_isolation's ''::uuid
        // cast would fail the whole query. The nil UUID matches no real user_id.
        return dsl.connectionResult(conn -> {
            try (var set = conn.prepareStatement(
                    "SELECT set_config('app.internal_owner_lookup', 'true', false), "
                            + "set_config('app.current_user_id', '00000000-0000-0000-0000-000000000000', false)")) {
                set.execute();
            }
            try {
                return using(conn)
                        .select(field(name("account_id"), UUID.class), field(name("user_id"), UUID.class))
                        .from(table(name(SCHEMA, TABLE)))
                        .where(field(name("statement_id")).eq(statementId))
                        .fetchOptional(r -> new StatementOwner(
                                r.get(field(name("account_id"), UUID.class)),
                                r.get(field(name("user_id"), UUID.class))));
            } finally {
                // Reset so a pooled connection never leaves this flag on for a later,
                // unrelated query — same reasoning as RlsExecuteListener.end().
                try (var reset = conn.prepareStatement("RESET app.internal_owner_lookup; RESET app.current_user_id")) {
                    reset.execute();
                } catch (java.sql.SQLException ignored) {
                }
            }
        });
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
                                field(name("content_fingerprint"), String.class),
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
                record.get("content_fingerprint", String.class),
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
                record.get("content_fingerprint", String.class),
                record.get("opening_date", LocalDate.class),
                record.get("closing_date", LocalDate.class),
                record.get("tx_count", Integer.class),
                record.get("pending_count", Integer.class),
                record.get("approved_count", Integer.class));
    }
}
