package com.fintracker.ledger.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} support for background jobs such as the REQ-5.1
 * month-end budget closure ({@link com.fintracker.ledger.budget.scheduler.BudgetPeriodCloser}).
 * Kept in a dedicated configuration class so tests can exclude scheduling wholesale.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
