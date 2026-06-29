package com.fintracker.ledger.bill.service.impl;

import com.fintracker.ledger.bill.exception.BillNotFoundException;
import com.fintracker.ledger.bill.dto.UpcomingBillDto;
import com.fintracker.ledger.bill.repository.BillRepository;
import com.fintracker.ledger.bill.service.BillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class BillServiceImpl implements BillService {

    private static final Logger log = LoggerFactory.getLogger(BillServiceImpl.class);

    private final BillRepository billRepository;

    public BillServiceImpl(BillRepository billRepository) {
        this.billRepository = billRepository;
    }

    @Override
    public List<UpcomingBillDto> getUpcomingBills(UUID userId) {
        return billRepository.findActiveBillsByUserId(userId);
    }

    @Override
    public void markBillAsPaid(UUID billId, UUID transactionId, UUID userId) {
        billRepository.findByIdAndUserId(billId, userId)
                .orElseThrow(() -> new BillNotFoundException(billId));

        LocalDate paidForMonth = LocalDate.now().withDayOfMonth(1);
        billRepository.recordPayment(billId, paidForMonth, transactionId);
        log.info("Recorded bill payment billId={} month={}", billId, paidForMonth);
    }

    @Override
    public BigDecimal sumPaidBillsForMonth(UUID userId, LocalDate monthStart) {
        return billRepository.sumPaidBillsForMonth(userId, monthStart);
    }
}
