package com.fintracker.ledger.transaction.service;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.repository.StatementRepository;
import com.fintracker.ledger.statement.service.StatementService;
import com.fintracker.ledger.transaction.dto.BulkCreateTransactionsRequest;
import com.fintracker.ledger.transaction.dto.ManualTransactionRequest;
import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.repository.TransactionRepository;
import com.fintracker.ledger.transaction.service.impl.TransactionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TXT-01: every stored transaction carries one of the five types plus a direction and currency.
 * Covers the statement bulk-create path, manual entry, and split children.
 */
@ExtendWith(MockitoExtension.class)
class TransactionTypeServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private StatementRepository statementRepository;
    @Mock private StatementService statementService;
    @Mock private AccountRepository accountRepository;

    private TransactionService transactionService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionServiceImpl(
                transactionRepository, statementRepository, statementService, accountRepository);
        userId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("bulkCreateFromStatement()")
    class BulkCreate {

        private UUID statementId;

        @BeforeEach
        void ownedStatement() {
            statementId = UUID.randomUUID();
            var statement = new Statement(statementId, UUID.randomUUID(), "statements/x/y/z.csv",
                    LocalDate.of(2026, 8, 1), Statement.StatementStatus.PROCESSING, "desc",
                    OffsetDateTime.now(), "CSV", "chase", "0".repeat(64), null,
                    LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), 0, 0, 0);
            when(statementRepository.findByIdAndUserId(statementId, userId)).thenReturn(Optional.of(statement));
        }

        private BulkCreateTransactionsRequest.TransactionLine line(String type, String direction, String currency) {
            return new BulkCreateTransactionsRequest.TransactionLine(
                    LocalDate.of(2026, 8, 15), "Store", new BigDecimal("12.00"), "Shopping", null,
                    type, "a".repeat(64), direction, currency);
        }

        @SuppressWarnings("unchecked")
        private List<Transaction> insertedRows() {
            var captor = ArgumentCaptor.forClass(List.class);
            verify(transactionRepository).bulkInsertIgnoringDuplicates(eq(statementId), captor.capture());
            return (List<Transaction>) captor.getValue();
        }

        private void stubInsertAll() {
            when(transactionRepository.bulkInsertIgnoringDuplicates(eq(statementId), anyList()))
                    .thenAnswer(i -> ((List<?>) i.getArgument(1)).size());
        }

        @ParameterizedTest
        @CsvSource({"EXPENSE,DEBIT", "INCOME,CREDIT", "REFUND,CREDIT", "TRANSFER,DEBIT", "ADJUSTMENT,CREDIT"})
        @DisplayName("each of the five types is stored with its direction")
        void storesEachTypeWithItsDirection(String type, String direction) {
            stubInsertAll();

            var result = transactionService.bulkCreateFromStatement(statementId, userId,
                    List.of(line(type, direction, null)));

            assertThat(result.failedRows()).isEmpty();
            var row = insertedRows().get(0);
            assertThat(row.type()).isEqualTo(Transaction.TransactionType.valueOf(type));
            assertThat(row.direction()).isEqualTo(Transaction.TransactionDirection.valueOf(direction));
        }

        @ParameterizedTest
        @CsvSource({"CREDIT,INCOME", "DEBIT,EXPENSE"})
        @DisplayName("a missing type defaults to INCOME for a credit and EXPENSE for a debit")
        void missingTypeDefaultsFromDirection(String direction, String expectedType) {
            stubInsertAll();

            var result = transactionService.bulkCreateFromStatement(statementId, userId,
                    List.of(line(null, direction, null)));

            assertThat(result.failedRows()).isEmpty();
            assertThat(insertedRows().get(0).type()).isEqualTo(Transaction.TransactionType.valueOf(expectedType));
        }

        @Test
        @DisplayName("a missing direction is reported as a failedRow")
        void missingDirectionIsAFailedRow() {
            var result = transactionService.bulkCreateFromStatement(statementId, userId,
                    List.of(line("EXPENSE", null, null)));

            assertThat(result.failedRows()).hasSize(1);
            verify(transactionRepository, never()).bulkInsertIgnoringDuplicates(any(), anyList());
        }

        @Test
        @DisplayName("a direction other than DEBIT or CREDIT is reported as a failedRow")
        void unknownDirectionIsAFailedRow() {
            var result = transactionService.bulkCreateFromStatement(statementId, userId,
                    List.of(line("EXPENSE", "OUT", null)));

            assertThat(result.failedRows()).hasSize(1);
            verify(transactionRepository, never()).bulkInsertIgnoringDuplicates(any(), anyList());
        }

        @Test
        @DisplayName("currency defaults to USD when omitted and is kept when supplied")
        void currencyDefaultsToUsd() {
            when(transactionRepository.bulkInsertIgnoringDuplicates(eq(statementId), anyList())).thenReturn(2);

            transactionService.bulkCreateFromStatement(statementId, userId, List.of(
                    line("EXPENSE", "DEBIT", null),
                    new BulkCreateTransactionsRequest.TransactionLine(
                            LocalDate.of(2026, 8, 15), "Store", new BigDecimal("12.00"), "Shopping", null,
                            "EXPENSE", "b".repeat(64), "DEBIT", "CAD")));

            assertThat(insertedRows()).extracting(Transaction::currency).containsExactly("USD", "CAD");
        }

        @Test
        @DisplayName("a currency that is not a 3-letter code is reported as a failedRow")
        void invalidCurrencyIsAFailedRow() {
            var result = transactionService.bulkCreateFromStatement(statementId, userId,
                    List.of(line("EXPENSE", "DEBIT", "dollars")));

            assertThat(result.failedRows()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("createManualTransaction()")
    class ManualEntry {

        private ManualTransactionRequest request(String type, String direction, String currency) {
            return new ManualTransactionRequest(UUID.randomUUID(), new BigDecimal("-30.00"),
                    "Store", "Shopping", List.of(), LocalDate.of(2026, 8, 1), type, direction, currency);
        }

        private Transaction saved() {
            var captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("stores the requested type, direction and currency")
        void storesTypeDirectionAndCurrency() {
            when(accountRepository.existsByIdAndUserId(any(), eq(userId))).thenReturn(true);
            when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            transactionService.createManualTransaction(request("TRANSFER", "DEBIT", "EUR"), userId);

            assertThat(saved().type()).isEqualTo(Transaction.TransactionType.TRANSFER);
            assertThat(saved().direction()).isEqualTo(Transaction.TransactionDirection.DEBIT);
            assertThat(saved().currency()).isEqualTo("EUR");
        }

        @Test
        @DisplayName("currency defaults to USD when omitted")
        void currencyDefaultsToUsd() {
            when(accountRepository.existsByIdAndUserId(any(), eq(userId))).thenReturn(true);
            when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            transactionService.createManualTransaction(request("EXPENSE", "DEBIT", null), userId);

            assertThat(saved().currency()).isEqualTo("USD");
        }

        @ParameterizedTest
        @CsvSource({"CREDIT,INCOME", "DEBIT,EXPENSE"})
        @DisplayName("a missing type defaults to INCOME for a credit and EXPENSE for a debit")
        void missingTypeDefaultsFromDirection(String direction, String expectedType) {
            when(accountRepository.existsByIdAndUserId(any(), eq(userId))).thenReturn(true);
            when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            transactionService.createManualTransaction(request(null, direction, null), userId);

            assertThat(saved().type()).isEqualTo(Transaction.TransactionType.valueOf(expectedType));
        }

        @Test
        @DisplayName("a legacy type (PURCHASE) is rejected")
        void legacyTypeIsRejected() {
            when(accountRepository.existsByIdAndUserId(any(), eq(userId))).thenReturn(true);

            assertThatThrownBy(() -> transactionService.createManualTransaction(
                    request("PURCHASE", "DEBIT", null), userId))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a missing direction is rejected")
        void missingDirectionIsRejected() {
            when(accountRepository.existsByIdAndUserId(any(), eq(userId))).thenReturn(true);

            assertThatThrownBy(() -> transactionService.createManualTransaction(
                    request("EXPENSE", null, null), userId))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(transactionRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("split children inherit the parent's type, direction and currency")
    void splitChildrenInheritTypeDirectionAndCurrency() {
        var parentId = UUID.randomUUID();
        var parent = new Transaction(parentId, UUID.randomUUID(), null, null, null,
                new BigDecimal("100.00"), "Store", "Shopping", null, List.of(),
                LocalDate.now(), Transaction.TransactionSource.STATEMENT_UPLOAD,
                Transaction.TransactionType.REFUND, Transaction.TransactionStatus.PENDING,
                false, false, null, null,
                Transaction.TransactionDirection.CREDIT, "CAD", null, null);
        when(transactionRepository.findByIdAndUserId(parentId, userId)).thenReturn(Optional.of(parent));
        when(transactionRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        var children = transactionService.splitTransaction(parentId, List.of(
                new TransactionService.SplitRequest(new BigDecimal("60.00"), "Shopping"),
                new TransactionService.SplitRequest(new BigDecimal("40.00"), "Groceries")), userId);

        assertThat(children).allSatisfy(child -> {
            assertThat(child.type()).isEqualTo(Transaction.TransactionType.REFUND);
            assertThat(child.direction()).isEqualTo(Transaction.TransactionDirection.CREDIT);
            assertThat(child.currency()).isEqualTo("CAD");
        });
    }
}
