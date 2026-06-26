package com.fintracker.ledger.budget.service;

import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.repository.BudgetRepository;
import com.fintracker.ledger.budget.service.BudgetService;
import com.fintracker.ledger.budget.service.impl.BudgetServiceImpl;
import com.fintracker.ledger.budget.exception.PastMonthModificationException;
import com.fintracker.ledger.transaction.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BudgetService Unit Tests")
class BudgetServiceTest {

    @Mock private BudgetRepository budgetRepository;
    @Mock private TransactionService transactionService;

    private BudgetService budgetService;

    @BeforeEach
    void setUp() {
        budgetService = new BudgetServiceImpl(budgetRepository, transactionService);
    }

    @Test
    @DisplayName("upsertBudget() should reject writes to past months")
    void shouldRejectPastMonthModification() {
        var pastMonth = LocalDate.now().minusMonths(1).withDayOfMonth(1);

        assertThatThrownBy(() -> budgetService.upsertBudget(UUID.randomUUID(), pastMonth, List.of()))
                .isInstanceOf(PastMonthModificationException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    @DisplayName("getBudgetForMonth() should clone previous month as base template when none exists")
    void shouldClonePreviousMonthAsTemplate() {
        var userId = UUID.randomUUID();
        var newMonth = LocalDate.now().withDayOfMonth(1);
        var prevBudgetId = UUID.randomUUID();
        var previousBudget = new Budget(prevBudgetId, userId, newMonth.minusMonths(1), 1,
                "Previous Budget",
                List.of(new BudgetLine(UUID.randomUUID(), prevBudgetId, "Groceries",
                        new BigDecimal("500.00"), "Description", BigDecimal.ZERO)), null);

        when(budgetRepository.findByUserAndMonth(userId, newMonth)).thenReturn(Optional.empty());
        when(budgetRepository.findLatestByUserId(userId)).thenReturn(Optional.of(previousBudget));
        when(budgetRepository.save(any())).thenAnswer(i -> {
            Budget b = i.getArgument(0);
            return new Budget(UUID.randomUUID(), b.userId(), b.effectiveMonth(), b.version(), b.description(), b.lines(), null);
        });

        budgetService.getBudgetForMonth(userId, newMonth);

        verify(budgetRepository).save(argThat(b ->
                b.effectiveMonth().equals(newMonth) &&
                b.lines().stream().anyMatch(l -> l.category().equals("Groceries"))));
    }
}
