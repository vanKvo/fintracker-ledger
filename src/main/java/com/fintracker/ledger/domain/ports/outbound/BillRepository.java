package com.fintracker.ledger.domain.ports.outbound;

import com.fintracker.ledger.domain.model.UpcomingBill;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound persistence port for recurring bills and payment tracking. */
public interface BillRepository {

    List<UpcomingBill> findActiveBillsByUserId(UUID userId);

    Optional<UpcomingBill> findById(UUID billId);

    /** Returns total amount of bills already paid in the given month for net saving calculation. */
    BigDecimal sumPaidBillsForMonth(UUID userId, LocalDate monthStart);

    void recordPayment(UUID billId, LocalDate paidForMonth, UUID transactionId);
}
