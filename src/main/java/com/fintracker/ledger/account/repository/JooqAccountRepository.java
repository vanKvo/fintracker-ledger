package com.fintracker.ledger.account.repository;

import com.fintracker.ledger.account.dto.AccountDto;
import org.jooq.Record;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.jooq.impl.DSL.*;

/** Outbound Adapter: jOOQ implementation of {@link AccountRepository}. */
@Repository
public class JooqAccountRepository implements AccountRepository {

    private static final String SCHEMA = "ledger";
    private static final String TABLE  = "accounts";

    private final DSLContext dsl;

    public JooqAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public BigDecimal sumTotalBalanceByUserId(UUID userId) {
        return dsl.select(DSL.coalesce(sum(field("current_balance", BigDecimal.class)), BigDecimal.ZERO))
                .from(table(name(SCHEMA, TABLE)))
                .where(field("user_id").eq(userId))
                .fetchOneInto(BigDecimal.class);
    }

    @Override
    public List<AccountDto> findAccountsByUserId(UUID userId) {
        return dsl.selectFrom(table(name(SCHEMA, TABLE)))
                .where(field("user_id").eq(userId))
                .fetch(this::mapToAccountDto);
    }

    @Override
    public boolean existsByIdAndUserId(UUID accountId, UUID userId) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(table(name(SCHEMA, TABLE)))
                        .where(field("account_id").eq(accountId))
                        .and(field("user_id").eq(userId))
        );
    }

    @Override
    public AccountDto insert(UUID userId, String accountName, String accountType,
                              String accountNumber, String owner, String syncMode) {
        var id = UUID.randomUUID();
        dsl.insertInto(table(name(SCHEMA, TABLE)))
                .set(field("account_id"), id)
                .set(field("user_id"), userId)
                .set(field("account_name"), accountName)
                .set(field("account_type"), accountType)
                .set(field("account_number"), accountNumber)
                .set(field("owner"), owner)
                .set(field("sync_mode"), syncMode)
                .execute();

        return findByIdInternal(id);
    }

    @Override
    public AccountDto update(UUID accountId, String accountName, String accountType,
                              String accountNumber, String owner, String syncMode) {
        // COALESCE-against-self: only overwrite a column when the caller actually supplied a new
        // value, so a PATCH with e.g. only accountName set doesn't null out the rest.
        dsl.update(table(name(SCHEMA, TABLE)))
                .set(field("account_name"), coalesce(val(accountName), field("account_name", String.class)))
                .set(field("account_type"), coalesce(val(accountType), field("account_type", String.class)))
                .set(field("account_number"), coalesce(val(accountNumber), field("account_number", String.class)))
                .set(field("owner"), coalesce(val(owner), field("owner", String.class)))
                .set(field("sync_mode"), coalesce(val(syncMode), field("sync_mode", String.class)))
                .where(field("account_id").eq(accountId))
                .execute();

        return findByIdInternal(accountId);
    }

    private AccountDto findByIdInternal(UUID accountId) {
        return dsl.selectFrom(table(name(SCHEMA, TABLE)))
                .where(field("account_id").eq(accountId))
                .fetchOptional(this::mapToAccountDto)
                .orElseThrow();
    }

    private AccountDto mapToAccountDto(Record record) {
        return new AccountDto(
                record.get("account_id", UUID.class),
                record.get("user_id", UUID.class),
                record.get("account_name", String.class),
                record.get("account_type", String.class),
                record.get("account_number", String.class),
                record.get("owner", String.class),
                record.get("current_balance", BigDecimal.class),
                record.get("sync_mode", String.class),
                record.get("created_at", OffsetDateTime.class)
        );
    }
}
