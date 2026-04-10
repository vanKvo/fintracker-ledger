package com.fintracker.ledger.infrastructure.persistence;

import com.fintracker.ledger.domain.model.UpcomingBill;
import com.fintracker.ledger.domain.ports.outbound.BillRepository;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.jooq.impl.DSL.*;

/** Outbound Adapter: jOOQ implementation of {@link BillRepository}. */
public class JooqBillRepository implements BillRepository {

    private final DSLContext dsl;

    public JooqBillRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<UpcomingBill> findActiveBillsByUserId(UUID userId) {
        return dsl.selectFrom(table(name("ledger", "upcoming_bills")))
                .where(field("user_id").eq(userId))
                .and(field("status").eq("ACTIVE"))
                .orderBy(field("due_date_day").asc())
                .fetch(r -> new UpcomingBill(
                        r.get("bill_id", UUID.class),
                        r.get("user_id", UUID.class),
                        r.get("name", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("due_date_day", Integer.class),
                        r.get("category", String.class),
                        r.get("description", String.class),
                        UpcomingBill.BillStatus.valueOf(r.get("status", String.class)),
                        r.get("created_at", OffsetDateTime.class)
                ));
    }

    @Override
    public Optional<UpcomingBill> findById(UUID billId) {
        return dsl.selectFrom(table(name("ledger", "upcoming_bills")))
                .where(field("bill_id").eq(billId))
                .fetchOptional(r -> new UpcomingBill(
                        r.get("bill_id", UUID.class),
                        r.get("user_id", UUID.class),
                        r.get("name", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("due_date_day", Integer.class),
                        r.get("category", String.class),
                        r.get("description", String.class),
                        UpcomingBill.BillStatus.valueOf(r.get("status", String.class)),
                        r.get("created_at", OffsetDateTime.class)
                ));
    }

    @Override
    public BigDecimal sumPaidBillsForMonth(UUID userId, LocalDate monthStart) {
        return dsl.select(DSL.coalesce(
                        sum(field(name("ledger", "upcoming_bills", "amount"), BigDecimal.class)),
                        BigDecimal.ZERO))
                .from(table(name("ledger", "bill_payments")))
                .join(table(name("ledger", "upcoming_bills")))
                .on(field(name("ledger", "bill_payments", "bill_id"))
                        .eq(field(name("ledger", "upcoming_bills", "bill_id"))))
                .where(field(name("ledger", "upcoming_bills", "user_id")).eq(userId))
                .and(field(name("ledger", "bill_payments", "paid_for_month")).eq(monthStart))
                .fetchOneInto(BigDecimal.class);
    }

    @Override
    public void recordPayment(UUID billId, LocalDate paidForMonth, UUID transactionId) {
        // ON CONFLICT DO NOTHING ensures idempotency via UNIQUE(bill_id, paid_for_month)
        dsl.execute(
                """
                INSERT INTO ledger.bill_payments (payment_id, bill_id, paid_for_month, transaction_id)
                VALUES (gen_random_uuid(), ?, ?, ?)
                ON CONFLICT (bill_id, paid_for_month) DO NOTHING
                """,
                billId, paidForMonth, transactionId
        );
    }
}
