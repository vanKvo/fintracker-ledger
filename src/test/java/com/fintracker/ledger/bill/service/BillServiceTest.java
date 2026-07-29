package com.fintracker.ledger.bill.service;

import com.fintracker.ledger.bill.exception.BillNotFoundException;
import com.fintracker.ledger.bill.dto.UpcomingBillDto;
import com.fintracker.ledger.bill.repository.BillRepository;
import com.fintracker.ledger.bill.service.impl.BillServiceImpl;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BillService Unit Tests")
class BillServiceTest {

    @Mock
    private BillRepository billRepository;

    private BillService billService;

    @BeforeEach
    void setUp() {
        billService = new BillServiceImpl(billRepository);
    }

    @Test
    @DisplayName("getUpcomingBills should return active bills from repository")
    void shouldReturnUpcomingBills() {
        var userId = UUID.randomUUID();
        var bill = new UpcomingBillDto(UUID.randomUUID(), userId, "Internet", new BigDecimal("79.99"),
                15, "monthly", "utilities", UpcomingBillDto.BillStatus.ACTIVE, java.time.OffsetDateTime.now());
        when(billRepository.findActiveBillsByUserId(userId)).thenReturn(List.of(bill));

        var result = billService.getUpcomingBills(userId);

        assertThat(result).hasSize(1).contains(bill);
        verify(billRepository).findActiveBillsByUserId(userId);
    }

    @Test
    @DisplayName("markBillAsPaid should record payment when bill exists")
    void shouldMarkBillAsPaid() {
        var billId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        var bill = new UpcomingBillDto(billId, userId, "Internet", new BigDecimal("79.99"),
                15, "monthly", "utilities", UpcomingBillDto.BillStatus.ACTIVE, java.time.OffsetDateTime.now());

        when(billRepository.findByIdAndUserId(billId, userId)).thenReturn(Optional.of(bill));

        billService.markBillAsPaid(billId, transactionId, userId);

        verify(billRepository).recordPayment(eq(billId), any(LocalDate.class), eq(transactionId));
    }

    @Test
    @DisplayName("markBillAsPaid should throw BillNotFoundException when bill does not exist")
    void shouldThrowWhenBillNotFound() {
        var billId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        when(billRepository.findByIdAndUserId(billId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> billService.markBillAsPaid(billId, transactionId, userId))
                .isInstanceOf(BillNotFoundException.class)
                .hasMessageContaining("Upcoming bill not found");

        verify(billRepository, never()).recordPayment(any(), any(), any());
    }

    @Test
    @DisplayName("sumPaidBillsForMonth should return sum from repository")
    void shouldReturnSumOfPaidBills() {
        var userId = UUID.randomUUID();
        var monthStart = LocalDate.now().withDayOfMonth(1);
        var expectedSum = new BigDecimal("150.00");

        when(billRepository.sumPaidBillsForMonth(userId, monthStart)).thenReturn(expectedSum);

        var result = billService.sumPaidBillsForMonth(userId, monthStart);

        assertThat(result).isEqualTo(expectedSum);
        verify(billRepository).sumPaidBillsForMonth(userId, monthStart);
    }
}
