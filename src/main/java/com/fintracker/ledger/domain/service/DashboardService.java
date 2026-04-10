package com.fintracker.ledger.domain.service;

import com.fintracker.ledger.domain.model.DashboardSummary;
import com.fintracker.ledger.domain.model.UpcomingBill;
import com.fintracker.ledger.domain.ports.outbound.AccountRepository;
import com.fintracker.ledger.domain.ports.outbound.BillRepository;
import com.fintracker.ledger.domain.ports.outbound.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Domain Service: Dashboard Module.
 * Aggregates financial metrics needed for the Dashboard view.
 * All calculations are pushed to the database via jOOQ SUM() aggregations
 * to minimize data transferred over the network.
 */
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BillRepository billRepository;

    public DashboardService(AccountRepository accountRepository,
                            TransactionRepository transactionRepository,
                            BillRepository billRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.billRepository = billRepository;
    }

    /**
     * Computes the five core Dashboard financial metrics:
     * Total Balance, Monthly Income, Monthly Expenses, Cash Flow, Net Saving.
     */
    public DashboardSummary getAggregations(UUID userId) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        BigDecimal totalBalance    = accountRepository.sumTotalBalanceByUserId(userId);
        BigDecimal monthlyIncome   = transactionRepository.sumMonthlyIncome(userId, monthStart, monthEnd);
        BigDecimal monthlyExpenses = transactionRepository.sumMonthlyExpenses(userId, monthStart, monthEnd);
        BigDecimal cashFlow        = monthlyIncome.subtract(monthlyExpenses);
        BigDecimal paidBills       = billRepository.sumPaidBillsForMonth(userId, monthStart);
        BigDecimal netSaving       = cashFlow.subtract(paidBills);

        log.debug("Dashboard aggregations computed for userId={} month={}", userId, monthStart);
        return new DashboardSummary(totalBalance, monthlyIncome, monthlyExpenses, cashFlow, netSaving);
    }

    /** Returns the chronological list of active bills for the Dashboard widget. */
    public List<UpcomingBill> getUpcomingBills(UUID userId) {
        return billRepository.findActiveBillsByUserId(userId);
    }

    /**
     * Records a bill payment for the current month.
     * Idempotency is guaranteed by the UNIQUE constraint on (bill_id, paid_for_month).
     */
    public void markBillAsPaid(UUID billId, UUID transactionId) {
        billRepository.findById(billId)
                .orElseThrow(() -> new BillNotFoundException(billId));

        LocalDate paidForMonth = LocalDate.now().withDayOfMonth(1);
        billRepository.recordPayment(billId, paidForMonth, transactionId);
        log.info("Recorded bill payment billId={} month={}", billId, paidForMonth);
    }

    public static class BillNotFoundException extends RuntimeException {
        public BillNotFoundException(UUID id) { super("Upcoming bill not found: " + id); }
    }
}
