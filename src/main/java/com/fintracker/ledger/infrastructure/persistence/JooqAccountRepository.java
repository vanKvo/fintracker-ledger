package com.fintracker.ledger.infrastructure.persistence;

import com.fintracker.ledger.domain.ports.outbound.AccountRepository;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.math.BigDecimal;
import java.util.UUID;

import static org.jooq.impl.DSL.*;

/** Outbound Adapter: jOOQ implementation of {@link AccountRepository}. */
public class JooqAccountRepository implements AccountRepository {

    private final DSLContext dsl;

    public JooqAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public BigDecimal sumTotalBalanceByUserId(UUID userId) {
        return dsl.select(DSL.coalesce(sum(field("current_balance", BigDecimal.class)), BigDecimal.ZERO))
                .from(table(name("ledger", "accounts")))
                .where(field("user_id").eq(userId))
                .fetchOneInto(BigDecimal.class);
    }

    @Override
    public java.util.List<com.fintracker.ledger.domain.model.Account> findAccountsByUserId(UUID userId) {
        return dsl.selectFrom(table(name("ledger", "accounts")))
                .where(field("user_id").eq(userId))
                .fetch(record -> new com.fintracker.ledger.domain.model.Account(
                        record.get("account_id", UUID.class),
                        record.get("user_id", UUID.class),
                        record.get("account_name", String.class),
                        record.get("account_type", String.class),
                        record.get("current_balance", BigDecimal.class),
                        record.get("sync_mode", String.class)
                ));
    }
}
