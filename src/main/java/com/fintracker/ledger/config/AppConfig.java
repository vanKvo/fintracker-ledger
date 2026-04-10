package com.fintracker.ledger.config;

import com.fintracker.ledger.domain.ports.outbound.*;
import com.fintracker.ledger.domain.service.*;
import com.fintracker.ledger.infrastructure.persistence.*;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dependency Injection wiring: binds Outbound Adapters to Domain Ports.
 * This is the only place where infrastructure dependencies are injected
 * into domain services, honoring the Hexagonal dependency rule:
 * infrastructure depends on domain, never the reverse.
 */
@Configuration
public class AppConfig {

    @Bean
    public TransactionRepository transactionRepository(DSLContext dsl) {
        return new JooqTransactionRepository(dsl);
    }

    @Bean
    public StatementRepository statementRepository(DSLContext dsl) {
        return new JooqStatementRepository(dsl);
    }

    @Bean
    public BudgetRepository budgetRepository(DSLContext dsl) {
        return new JooqBudgetRepository(dsl);
    }

    @Bean
    public BillRepository billRepository(DSLContext dsl) {
        return new JooqBillRepository(dsl);
    }

    @Bean
    public AccountRepository accountRepository(DSLContext dsl) {
        return new JooqAccountRepository(dsl);
    }

    @Bean
    public TransactionService transactionService(TransactionRepository transactionRepository,
                                                  StatementRepository statementRepository) {
        return new TransactionService(transactionRepository, statementRepository);
    }

    @Bean
    public BudgetService budgetService(BudgetRepository budgetRepository,
                                       TransactionRepository transactionRepository) {
        return new BudgetService(budgetRepository, transactionRepository);
    }

    @Bean
    public StatementService statementService(StatementRepository statementRepository) {
        return new StatementService(statementRepository);
    }

    @Bean
    public DashboardService dashboardService(AccountRepository accountRepository,
                                              TransactionRepository transactionRepository,
                                              BillRepository billRepository) {
        return new DashboardService(accountRepository, transactionRepository, billRepository);
    }
}
