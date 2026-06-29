package com.fintracker.ledger.bill.repository;

import com.fintracker.ledger.bill.dto.UpcomingBillDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillRepository {

    List<UpcomingBillDto> findActiveBillsByUserId(UUID userId);

    Optional<UpcomingBillDto> findByIdAndUserId(UUID billId, UUID userId);

    BigDecimal sumPaidBillsForMonth(UUID userId, LocalDate monthStart);

    void recordPayment(UUID billId, LocalDate paidForMonth, UUID transactionId);
}
