package com.fintracker.ledger.budget.scheduler;

import com.fintracker.ledger.budget.service.BudgetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * REQ-5.1 "Automated Period Closure": on the 1st day of every month at 00:00:00 UTC, every
 * ACTIVE budget whose period lies strictly before the current month is transitioned to CLOSED.
 *
 * <p>The cutoff is derived from the injected {@link Clock} (pinned to UTC in
 * {@link com.fintracker.ledger.config.TimeConfig}) rather than the server's default zone, so a
 * budget month never closes early or late because of deployment region.
 */
@Component
public class BudgetPeriodCloser {

    private static final Logger log = LoggerFactory.getLogger(BudgetPeriodCloser.class);

    private final BudgetService budgetService;
    private final Clock clock;

    public BudgetPeriodCloser(BudgetService budgetService, Clock clock) {
        this.budgetService = budgetService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 0 1 * *", zone = "UTC")
    public void closePastBudgets() {
        LocalDate cutoff = LocalDate.now(clock).withDayOfMonth(1);
        log.info("Month-end budget closure starting. cutoff={}", cutoff);
        int closed = budgetService.closePastBudgets(cutoff);
        log.info("Month-end budget closure finished. cutoff={} closedCount={}", cutoff, closed);
    }
}
