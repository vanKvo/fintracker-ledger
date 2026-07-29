package com.fintracker.ledger.transaction.repository;

import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.model.TransactionFilter;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.jooq.impl.DSL.*;

@Repository
public class JooqTransactionRepository implements TransactionRepository {

    private static final Logger log = LoggerFactory.getLogger(JooqTransactionRepository.class);
    private static final String SCHEMA   = "ledger";
    private static final String TX_TABLE = "transactions";
    private static final String SPLIT_CHILD_ALIAS = "split_child";

    private final DSLContext dsl;

    public JooqTransactionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<Transaction> findAll(TransactionFilter filter) {
        var query = dsl.select(table(name(SCHEMA, TX_TABLE)).asterisk())
                .from(table(name(SCHEMA, TX_TABLE)))
                .join(table(name(SCHEMA, "accounts")))
                .on(field(name(SCHEMA, TX_TABLE, "account_id"))
                        .eq(field(name(SCHEMA, "accounts", "account_id"))))
                .where(field(name(SCHEMA, "accounts", "user_id")).eq(filter.userId()))
                .and(filter.accountId() != null
                        ? field(name(SCHEMA, TX_TABLE, "account_id")).eq(filter.accountId()) : noCondition())
                .and(filter.merchantContains() != null && !filter.merchantContains().isBlank()
                        ? field(name(SCHEMA, TX_TABLE, "merchant")).likeIgnoreCase("%" + filter.merchantContains() + "%") : noCondition())
                .and(filter.dateFrom() != null
                        ? field(name(SCHEMA, TX_TABLE, "tx_date")).greaterOrEqual(filter.dateFrom()) : noCondition())
                .and(filter.dateTo() != null
                        ? field(name(SCHEMA, TX_TABLE, "tx_date")).lessOrEqual(filter.dateTo()) : noCondition())
                .and(filter.category() != null
                        ? field(name(SCHEMA, TX_TABLE, "category")).eq(filter.category()) : noCondition())
                .and(filter.status() != null
                        ? field(name(SCHEMA, TX_TABLE, "status")).eq(filter.status().name()) : noCondition())
                .and(filter.tags() != null && !filter.tags().isEmpty()
                        ? condition("{0} && {1}::text[]",
                                field(name(SCHEMA, TX_TABLE, "tags")),
                                val(filter.tags().toArray(String[]::new)))
                        : noCondition())
                .and(isNotSplitParent())
                .orderBy(field(name(SCHEMA, TX_TABLE, "tx_date")).desc())
                .limit(filter.size())
                .offset(filter.page() * filter.size());

        return query.fetch(this::mapToTransaction);
    }

    @Override
    public Optional<Transaction> findByIdAndUserId(UUID transactionId, UUID userId) {
        return dsl.selectFrom(table(name(SCHEMA, TX_TABLE)))
                .where(field("transaction_id").eq(transactionId))
                .and(field("user_id").eq(userId))
                .fetchOptional(this::mapToTransaction);
    }

    // Used only internally after INSERT to reload the persisted record.
    private Transaction findByIdInternal(UUID transactionId) {
        return dsl.selectFrom(table(name(SCHEMA, TX_TABLE)))
                .where(field("transaction_id").eq(transactionId))
                .fetchOptional(this::mapToTransaction)
                .orElseThrow();
    }

    @Override
    public Transaction save(Transaction transaction) {
        var id = UUID.randomUUID();
        dsl.insertInto(table(name(SCHEMA, TX_TABLE)))
                .set(field("transaction_id"), id)
                .set(field("account_id"), transaction.accountId())
                .set(field("statement_id"), transaction.statementId())
                .set(field("parent_transaction_id"), transaction.parentTransactionId())
                .set(field("external_tx_id"), transaction.externalTxId())
                .set(field("amount"), transaction.amount())
                .set(field("merchant"), transaction.merchant())
                .set(field("category"), transaction.category())
                .set(field("description"), transaction.description())
                .set(field("tags"), transaction.tags() != null
                        ? transaction.tags().toArray(String[]::new) : new String[0])
                .set(field("tx_date"), transaction.txDate())
                .set(field("source"), transaction.source().name())
                .set(field("type"), transaction.type().name())
                .set(field("status"), transaction.status().name())
                .set(field("is_excluded"), transaction.isExcluded())
                // is_manual has been a plain DEFAULT FALSE column (not DB-computed) since
                // V4__Rename_Transaction_Enums_And_Is_Manual_Default.sql — it must be set
                // explicitly here or every save() (including split children copying a manual
                // parent's isManual) would silently persist FALSE regardless of the passed-in
                // Transaction's value.
                .set(field("is_manual"), transaction.isManual())
                .execute();

        return findByIdInternal(id);
    }

    @Override
    public List<Transaction> saveAll(List<Transaction> transactions) {
        return transactions.stream().map(this::save).toList();
    }

    @Override
    public void updateStatus(UUID transactionId, Transaction.TransactionStatus newStatus) {
        dsl.update(table(name(SCHEMA, TX_TABLE)))
                .set(field("status"), newStatus.name())
                .where(field("transaction_id").eq(transactionId))
                .execute();
    }

    @Override
    public void updateCategory(UUID transactionId, String category) {
        dsl.update(table(name(SCHEMA, TX_TABLE)))
                .set(field("category"), category)
                .where(field("transaction_id").eq(transactionId))
                .execute();
    }

    @Override
    public void updateAmount(UUID transactionId, BigDecimal amount) {
        dsl.update(table(name(SCHEMA, TX_TABLE)))
                .set(field("amount"), amount)
                .where(field("transaction_id").eq(transactionId))
                .execute();
    }

    @Override
    public void appendTags(UUID transactionId, List<String> newTags) {
        dsl.execute(
                "UPDATE ledger.transactions SET tags = array_cat(tags, ?::text[]) WHERE transaction_id = ?",
                newTags.toArray(String[]::new), transactionId
        );
    }

    @Override
    public void toggleExcluded(UUID transactionId, boolean isExcluded) {
        dsl.update(table(name(SCHEMA, TX_TABLE)))
                .set(field("is_excluded"), isExcluded)
                .where(field("transaction_id").eq(transactionId))
                .execute();
    }

    @Override
    public void deleteManualTransaction(UUID transactionId) {
        dsl.deleteFrom(table(name(SCHEMA, TX_TABLE)))
                .where(field("transaction_id").eq(transactionId))
                .and(field("is_manual").isTrue())
                .execute();
    }

    @Override
    public int countPendingByStatementId(UUID statementId) {
        return dsl.selectCount()
                .from(table(name(SCHEMA, TX_TABLE)))
                .where(field("statement_id").eq(statementId))
                .and(field("status").eq(Transaction.TransactionStatus.PENDING.name()))
                .fetchOne(0, int.class);
    }

    @Override
    public BigDecimal sumMonthlyIncome(UUID userId, LocalDate monthStart, LocalDate monthEnd) {
        return dsl.select(DSL.coalesce(sum(field("amount", BigDecimal.class)), BigDecimal.ZERO))
                .from(table(name(SCHEMA, TX_TABLE)))
                .join(table(name(SCHEMA, "accounts"))).on(
                        field(name(SCHEMA, TX_TABLE, "account_id"))
                                .eq(field(name(SCHEMA, "accounts", "account_id"))))
                .where(field(name(SCHEMA, "accounts", "user_id")).eq(userId))
                .and(field(name(SCHEMA, TX_TABLE, "type")).eq("CREDIT"))
                .and(field(name(SCHEMA, TX_TABLE, "status")).eq("POSTED"))
                .and(field(name(SCHEMA, TX_TABLE, "is_excluded")).isFalse())
                .and(field(name(SCHEMA, TX_TABLE, "tx_date")).between(monthStart).and(monthEnd))
                .and(isNotSplitParent())
                .fetchOneInto(BigDecimal.class);
    }

    @Override
    public BigDecimal sumMonthlyExpenses(UUID userId, LocalDate monthStart, LocalDate monthEnd) {
        return dsl.select(DSL.coalesce(sum(field("amount", BigDecimal.class).abs()), BigDecimal.ZERO))
                .from(table(name(SCHEMA, TX_TABLE)))
                .join(table(name(SCHEMA, "accounts"))).on(
                        field(name(SCHEMA, TX_TABLE, "account_id"))
                                .eq(field(name(SCHEMA, "accounts", "account_id"))))
                .where(field(name(SCHEMA, "accounts", "user_id")).eq(userId))
                .and(field(name(SCHEMA, TX_TABLE, "type")).eq("PURCHASE"))
                .and(field(name(SCHEMA, TX_TABLE, "status")).eq("POSTED"))
                .and(field(name(SCHEMA, TX_TABLE, "is_excluded")).isFalse())
                .and(field(name(SCHEMA, TX_TABLE, "tx_date")).between(monthStart).and(monthEnd))
                .and(isNotSplitParent())
                .fetchOneInto(BigDecimal.class);
    }

    /**
     * REQ-5.1 "Spend Amount Initialization": the same aggregate as {@link #sumMonthlyExpenses},
     * narrowed to a single budget line's category. Category comparison is case-insensitive to
     * match {@link com.fintracker.ledger.transaction.model.TransactionCategory#resolve}, which
     * canonicalizes on write but tolerates legacy free-text casing already in the column.
     *
     * <p>The PURCHASE/POSTED/is_excluded/split-parent predicates are deliberately identical to
     * {@link #sumMonthlyExpenses} so that the per-category sums of a month reconcile to that
     * month's total rather than drifting from it.
     */
    @Override
    public BigDecimal sumMonthlyExpensesPerCategory(UUID userId, LocalDate monthStart, LocalDate monthEnd, String category) {
        return dsl.select(DSL.coalesce(sum(field("amount", BigDecimal.class).abs()), BigDecimal.ZERO))
                .from(table(name(SCHEMA, TX_TABLE)))
                .join(table(name(SCHEMA, "accounts"))).on(
                        field(name(SCHEMA, TX_TABLE, "account_id"))
                                .eq(field(name(SCHEMA, "accounts", "account_id"))))
                .where(field(name(SCHEMA, "accounts", "user_id")).eq(userId))
                .and(field(name(SCHEMA, TX_TABLE, "type")).eq("PURCHASE"))
                .and(field(name(SCHEMA, TX_TABLE, "status")).eq("POSTED"))
                .and(field(name(SCHEMA, TX_TABLE, "is_excluded")).isFalse())
                .and(field(name(SCHEMA, TX_TABLE, "category"), String.class).equalIgnoreCase(category))
                .and(field(name(SCHEMA, TX_TABLE, "tx_date")).between(monthStart).and(monthEnd))
                .and(isNotSplitParent())
                .fetchOneInto(BigDecimal.class);
    }

    /**
     * REQ-2.2 "Transaction Splitting": "the parent transaction must be dynamically hidden from
     * active views (WHERE NOT EXISTS) the moment child rows are written." Once a transaction has
     * been split, its children are what should count in listings/aggregates — without this, the
     * same money is counted once via the parent and again via its children the moment both end up
     * POSTED (nothing currently stops the parent from being approved independently of its children).
     */
    private Condition isNotSplitParent() {
        return notExists(
                select(val(1))
                        .from(table(name(SCHEMA, TX_TABLE)).as(SPLIT_CHILD_ALIAS))
                        .where(field(name(SPLIT_CHILD_ALIAS, "parent_transaction_id"))
                                .eq(field(name(SCHEMA, TX_TABLE, "transaction_id"))))
        );
    }

    private Transaction mapToTransaction(org.jooq.Record record) {
        return new Transaction(
                record.get("transaction_id", UUID.class),
                record.get("account_id", UUID.class),
                record.get("statement_id", UUID.class),
                record.get("parent_transaction_id", UUID.class),
                record.get("external_tx_id", String.class),
                record.get("amount", BigDecimal.class),
                record.get("merchant", String.class),
                record.get("category", String.class),
                record.get("description", String.class),
                List.of(),
                record.get("tx_date", LocalDate.class),
                Transaction.TransactionSource.valueOf(record.get("source", String.class)),
                Transaction.TransactionType.valueOf(record.get("type", String.class)),
                Transaction.TransactionStatus.valueOf(record.get("status", String.class)),
                record.get("is_excluded", Boolean.class),
                record.get("is_manual", Boolean.class),
                record.get("created_at", OffsetDateTime.class)
        );
    }
}
