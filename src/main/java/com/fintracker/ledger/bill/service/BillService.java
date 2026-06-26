package com.fintracker.ledger.bill.service;

import com.fintracker.ledger.bill.dto.UpcomingBillDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BillService {

    List<UpcomingBillDto> getUpcomingBills(UUID userId);

    void markBillAsPaid(UUID billId, UUID transactionId);

    BigDecimal sumPaidBillsForMonth(UUID userId, LocalDate monthStart);
}
