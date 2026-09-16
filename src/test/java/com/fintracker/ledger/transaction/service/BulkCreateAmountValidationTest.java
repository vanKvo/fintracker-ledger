package com.fintracker.ledger.transaction.service;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.repository.StatementRepository;
import com.fintracker.ledger.statement.service.StatementService;
import com.fintracker.ledger.transaction.dto.BulkCreateTransactionsRequest;
import com.fintracker.ledger.transaction.repository.TransactionRepository;
import com.fintracker.ledger.transaction.service.impl.TransactionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * REQ-STMT-02 row-level amount validation for {@code bulkCreateFromStatement}.
 *
 * <p><b>Why this class exists separately from {@link TransactionServiceTest}:</b>
 * {@code golden_tests/run_tests_f2p.sh} rewrites {@code TransactionServiceTest.java} verbatim on
 * every run, so any case added there is silently discarded the next time that script executes. The
 * four cases below were written against this codebase before that script existed and are not part
 * of its inventory; keeping them here preserves the coverage without fighting the generator.
 *
 * <p>What they pin is the boundary between what the DTO's Bean Validation can catch and what only
 * the service can: {@code amount} is {@code @NotNull} on the request record, but nothing there
 * constrains it to the {@code DECIMAL(15,2)} shape of {@code ledger.transactions.amount} or to the
 * non-zero CHECK constraint the table carries. Without a per-row check in the service, those rows
 * reach the multi-row INSERT and abort the entire batch at the database — exactly the
 * "one bad row never aborts the whole batch" constraint REQ-STMT-02 forbids.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("bulkCreateFromStatement() — amount validation")
class BulkCreateAmountValidationTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private StatementRepository statementRepository;
    @Mock private StatementService statementService;
    @Mock private AccountRepository accountRepository;

    private TransactionServiceImpl transactionService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionServiceImpl(
                transactionRepository, statementRepository, statementService, accountRepository);
        userId = UUID.randomUUID();
    }

    private Statement ownedStatement(UUID statementId, UUID accountId) {
        return new Statement(statementId, accountId, "statements/u/s/stmt.csv",
                LocalDate.of(2026, 9, 1), Statement.StatementStatus.PROCESSING, null,
                OffsetDateTime.now(), "CSV", "bank-a", "c".repeat(64), null,
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), 0, 0, 0);
    }

    private BulkCreateTransactionsRequest.TransactionLine line(
            String merchant, String amount, String type, String fingerprint) {
        return new BulkCreateTransactionsRequest.TransactionLine(
                LocalDate.of(2026, 8, 15), merchant, new BigDecimal(amount), "Groceries",
                null, type, fingerprint);
    }

    private UUID stubOwnedStatement(int insertedCount) {
        var statementId = UUID.randomUUID();
        when(statementRepository.findByIdAndUserId(statementId, userId))
                .thenReturn(Optional.of(ownedStatement(statementId, UUID.randomUUID())));
        when(transactionRepository.bulkInsertIgnoringDuplicates(eq(statementId), anyList()))
                .thenReturn(insertedCount);
        return statementId;
    }

    @Test
    @DisplayName("accepts both signs: a negative PURCHASE and a positive CREDIT are both valid")
    void shouldAcceptAmountsOfAnySign() {
        var statementId = stubOwnedStatement(2);

        var response = transactionService.bulkCreateFromStatement(statementId, userId, List.of(
                line("Purchase", "-25.00", "PURCHASE", "a".repeat(64)),
                line("Credit", "25.00", "CREDIT", "b".repeat(64))));

        assertThat(response.failedRows()).isEmpty();
        assertThat(response.insertedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("rejects exactly zero — the table's CHECK (amount != 0) would abort the batch")
    void shouldRejectExactlyZeroAmount() {
        var statementId = stubOwnedStatement(1);

        var response = transactionService.bulkCreateFromStatement(statementId, userId, List.of(
                line("Purchase", "-25.00", "PURCHASE", "a".repeat(64)),
                line("Zero", "0.00", "PURCHASE", "b".repeat(64))));

        assertThat(response.insertedCount()).isEqualTo(1);
        assertThat(response.failedRows()).hasSize(1);
        assertThat(response.failedRows().get(0).index()).isEqualTo(1);
        assertThat(response.failedRows().get(0).reason()).contains("zero");
    }

    @Test
    @DisplayName("rejects amounts overflowing DECIMAL(15,2) in either direction, accepting the "
            + "exact ceiling")
    void shouldRejectAmountOverflowingDecimal152() {
        var statementId = stubOwnedStatement(2);

        var response = transactionService.bulkCreateFromStatement(statementId, userId, List.of(
                line("Max", "9999999999999.99", "PURCHASE", "a".repeat(64)),        // ceiling — accepted
                line("Overflow", "10000000000000.00", "PURCHASE", "b".repeat(64)),  // 14 digits — rejected
                line("NegOverflow", "-10000000000000.00", "CREDIT", "c".repeat(64))));

        assertThat(response.insertedCount()).isEqualTo(2);
        assertThat(response.failedRows()).hasSize(2);
        assertThat(response.failedRows()).extracting(f -> f.index()).containsExactly(1, 2);
        assertThat(response.failedRows()).allSatisfy(f ->
                assertThat(f.reason()).contains("DECIMAL(15,2)"));
    }

    @Test
    @DisplayName("rejects sub-cent precision, but accepts trailing zeros and whole numbers — the "
            + "check is on significant scale, not the literal the pipeline happened to send")
    void shouldRejectSubCentPrecision() {
        var statementId = stubOwnedStatement(2);

        var response = transactionService.bulkCreateFromStatement(statementId, userId, List.of(
                line("TrailingZeros", "25.500", "PURCHASE", "a".repeat(64)),  // = 25.5 — accepted
                line("Integer", "600", "CREDIT", "b".repeat(64)),             // scale 0 — accepted
                line("SubCent", "25.555", "PURCHASE", "c".repeat(64))));      // rejected

        assertThat(response.insertedCount()).isEqualTo(2);
        assertThat(response.failedRows()).hasSize(1);
        assertThat(response.failedRows().get(0).index()).isEqualTo(2);
        assertThat(response.failedRows().get(0).reason()).contains("2 decimal places");
    }
}
