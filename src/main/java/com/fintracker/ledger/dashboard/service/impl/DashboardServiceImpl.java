package com.fintracker.ledger.dashboard.service.impl;

import com.fintracker.ledger.account.service.AccountService;
import com.fintracker.ledger.bill.service.BillService;
import com.fintracker.ledger.dashboard.model.DashboardSummary;
import com.fintracker.ledger.dashboard.service.DashboardService;
import com.fintracker.ledger.transaction.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class DashboardServiceImpl implements DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardServiceImpl.class);

    private final AccountService accountService;
    private final TransactionService transactionService;
    private final BillService billService;

    public DashboardServiceImpl(AccountService accountService,
                                TransactionService transactionService,
                                BillService billService) {
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.billService = billService;
    }

    @Override
    public DashboardSummary getAggregations(UUID userId) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        BigDecimal totalBalance    = accountService.sumTotalBalanceByUserId(userId);
        BigDecimal monthlyIncome   = transactionService.sumMonthlyIncome(userId, monthStart, monthEnd);
        BigDecimal monthlyExpenses = transactionService.sumMonthlyExpenses(userId, monthStart, monthEnd);
        BigDecimal cashFlow        = monthlyIncome.subtract(monthlyExpenses);
        BigDecimal paidBills       = billService.sumPaidBillsForMonth(userId, monthStart);
        BigDecimal netSaving       = cashFlow.subtract(paidBills);

        log.debug("Dashboard aggregations computed for userId={} month={}", userId, monthStart);
        return new DashboardSummary(totalBalance, monthlyIncome, monthlyExpenses, cashFlow, netSaving);
    }
}
