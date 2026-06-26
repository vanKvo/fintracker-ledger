package com.fintracker.ledger.transaction.service;

import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.service.StatementService;
import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.repository.TransactionRepository;
import com.fintracker.ledger.transaction.service.TransactionService;
import com.fintracker.ledger.transaction.service.impl.TransactionServiceImpl;
import com.fintracker.ledger.transaction.exception.IllegalStateTransitionException;
import com.fintracker.ledger.transaction.exception.SplitAmountMismatchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService Unit Tests")
class TransactionServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private StatementService statementService;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionServiceImpl(transactionRepository, statementService);
    }

    private Transaction pendingTransaction(UUID id, UUID statementId) {
        return new Transaction(id, UUID.randomUUID(), statementId, null, null,
                new BigDecimal("-100.00"), "Merchant", "Groceries", "Description", List.of(),
                LocalDate.now(), Transaction.TransactionSource.STATEMENT_UPLOAD,
                Transaction.TransactionType.SALE, Transaction.TransactionStatus.PENDING_APPROVAL,
                false, false, null);
    }

    private Transaction postedTransaction(UUID id) {
        return new Transaction(id, UUID.randomUUID(), null, null, null,
                new BigDecimal("-50.00"), "Shop", "Dining", "Description", List.of(),
                LocalDate.now(), Transaction.TransactionSource.MANUAL_ENTRY,
                Transaction.TransactionType.SALE, Transaction.TransactionStatus.POSTED,
                false, true, null);
    }

    @Nested
    @DisplayName("approveTransaction()")
    class ApproveTransaction {

        @Test
        @DisplayName("should approve a PENDING_APPROVAL transaction")
        void shouldApprovePendingTransaction() {
            var txId = UUID.randomUUID();
            var statementId = UUID.randomUUID();
            when(transactionRepository.findById(txId))
                    .thenReturn(Optional.of(pendingTransaction(txId, statementId)));
            when(transactionRepository.countPendingByStatementId(statementId)).thenReturn(0);

            transactionService.approveTransaction(txId);

            verify(transactionRepository).updateStatus(txId, Transaction.TransactionStatus.POSTED);
        }

        @Test
        @DisplayName("should mark statement COMPLETED when last pending transaction is approved")
        void shouldCompleteStatementWhenLastTransactionApproved() {
            var txId = UUID.randomUUID();
            var statementId = UUID.randomUUID();
            when(transactionRepository.findById(txId))
                    .thenReturn(Optional.of(pendingTransaction(txId, statementId)));
            when(transactionRepository.countPendingByStatementId(statementId)).thenReturn(0);

            transactionService.approveTransaction(txId);

            verify(statementService).updateStatus(statementId, Statement.StatementStatus.COMPLETED);
        }

        @Test
        @DisplayName("should NOT complete statement when other pending transactions remain")
        void shouldNotCompleteStatementWhenOthersPending() {
            var txId = UUID.randomUUID();
            var statementId = UUID.randomUUID();
            when(transactionRepository.findById(txId))
                    .thenReturn(Optional.of(pendingTransaction(txId, statementId)));
            when(transactionRepository.countPendingByStatementId(statementId)).thenReturn(3);

            transactionService.approveTransaction(txId);

            verify(statementService, never()).updateStatus(any(), any());
        }

        @Test
        @DisplayName("should throw when approving an already POSTED transaction")
        void shouldThrowWhenApprovingAlreadyPosted() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findById(txId))
                    .thenReturn(Optional.of(postedTransaction(txId)));

            assertThatThrownBy(() -> transactionService.approveTransaction(txId))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("splitTransaction()")
    class SplitTransaction {

        @Test
        @DisplayName("should split parent into children when amounts match exactly")
        void shouldSplitSuccessfully() {
            var parentId = UUID.randomUUID();
            var parent = pendingTransaction(parentId, null);
            when(transactionRepository.findById(parentId)).thenReturn(Optional.of(parent));
            when(transactionRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

            var splits = List.of(
                    new TransactionService.SplitRequest(new BigDecimal("60.00"), "Groceries"),
                    new TransactionService.SplitRequest(new BigDecimal("40.00"), "Dining"));

            var result = transactionService.splitTransaction(parentId, splits);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("should throw SplitAmountMismatchException when split totals diverge")
        void shouldThrowOnAmountMismatch() {
            var parentId = UUID.randomUUID();
            when(transactionRepository.findById(parentId))
                    .thenReturn(Optional.of(pendingTransaction(parentId, null)));

            var badSplits = List.of(
                    new TransactionService.SplitRequest(new BigDecimal("50.00"), "Groceries"),
                    new TransactionService.SplitRequest(new BigDecimal("30.00"), "Dining"));

            assertThatThrownBy(() -> transactionService.splitTransaction(parentId, badSplits))
                    .isInstanceOf(SplitAmountMismatchException.class);
        }
    }

    @Nested
    @DisplayName("deleteManualTransaction()")
    class DeleteManualTransaction {

        @Test
        @DisplayName("should reject deletion of a non-manual transaction")
        void shouldRejectDeletionOfNonManual() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findById(txId))
                    .thenReturn(Optional.of(pendingTransaction(txId, null)));

            assertThatThrownBy(() -> transactionService.deleteManualTransaction(txId))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("Only manual transactions");
        }
    }
}
