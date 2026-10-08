package com.fintracker.ledger.transaction.repository;

import com.fintracker.ledger.shared.UserContextHolder;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import com.fintracker.ledger.transaction.model.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TXT-01 / TXT-02 (transaction-types spec, Appendix A): the five-type taxonomy and the new
 * direction / currency / is_recurring / linked_transaction_id columns, enforced by the database,
 * and the dashboard totals that read them.
 */
class TransactionTypeIT extends AbstractIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    // ------------------------------------------------------------------ schema (TXT-01)

    @ParameterizedTest
    @CsvSource({"EXPENSE,DEBIT", "INCOME,CREDIT", "REFUND,CREDIT", "TRANSFER,DEBIT", "ADJUSTMENT,CREDIT"})
    @DisplayName("the database accepts each of the five transaction types")
    void acceptsEachOfTheFiveTypes(String type, String direction) throws SQLException {
        var accountId = insertAccount(UUID.randomUUID());

        var txId = insertTransaction(accountId, "-10.00", type, direction, "POSTED", LocalDate.now());

        assertThat(readColumn(txId, "type")).isEqualTo(type);
    }

    @ParameterizedTest
    @CsvSource({"EXPENSE,CREDIT", "INCOME,DEBIT", "REFUND,DEBIT"})
    @DisplayName("the database rejects EXPENSE as money in and INCOME or REFUND as money out")
    void rejectsTypeDirectionMismatch(String type, String direction) throws SQLException {
        var accountId = insertAccount(UUID.randomUUID());

        assertThatThrownBy(() -> insertTransaction(accountId, "-10.00", type, direction, "POSTED", LocalDate.now()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("transactions_type_direction_check");
    }

    @ParameterizedTest
    @CsvSource({"TRANSFER,DEBIT", "TRANSFER,CREDIT", "ADJUSTMENT,DEBIT", "ADJUSTMENT,CREDIT"})
    @DisplayName("TRANSFER and ADJUSTMENT may go either direction")
    void transferAndAdjustmentAcceptEitherDirection(String type, String direction) throws SQLException {
        var accountId = insertAccount(UUID.randomUUID());

        var txId = insertTransaction(accountId, "-10.00", type, direction, "POSTED", LocalDate.now());

        assertThat(readColumn(txId, "direction")).isEqualTo(direction);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PURCHASE", "CREDIT", "SALE", "RETURN", "UNKNOWN"})
    @DisplayName("the database rejects any type outside the five transaction types")
    void rejectsTypesOutsideTheFive(String type) throws SQLException {
        var accountId = insertAccount(UUID.randomUUID());

        assertThatThrownBy(() -> insertTransaction(accountId, "-10.00", type, "DEBIT", "POSTED", LocalDate.now()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("transactions_type_check");
    }

    @Test
    @DisplayName("the database rejects a transaction with no direction")
    void rejectsMissingDirection() throws SQLException {
        var accountId = insertAccount(UUID.randomUUID());

        assertThatThrownBy(() -> insertTransaction(accountId, "-10.00", "EXPENSE", null, "POSTED", LocalDate.now()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("direction");
    }

    @Test
    @DisplayName("the database rejects a direction other than DEBIT or CREDIT")
    void rejectsUnknownDirection() throws SQLException {
        var accountId = insertAccount(UUID.randomUUID());

        assertThatThrownBy(() -> insertTransaction(accountId, "-10.00", "EXPENSE", "OUT", "POSTED", LocalDate.now()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("transactions_direction_check");
    }

    @Test
    @DisplayName("currency defaults to USD; is_recurring and linked_transaction_id default to null")
    void newColumnDefaults() throws SQLException {
        var accountId = insertAccount(UUID.randomUUID());

        var txId = insertTransaction(accountId, "-10.00", "EXPENSE", "DEBIT", "POSTED", LocalDate.now());

        assertThat(readColumn(txId, "currency")).isEqualTo("USD");
        assertThat(readColumn(txId, "is_recurring")).isNull();
        assertThat(readColumn(txId, "linked_transaction_id")).isNull();
    }

    @Test
    @DisplayName("linked_transaction_id must reference an existing transaction")
    void linkedTransactionMustExist() throws SQLException {
        var accountId = insertAccount(UUID.randomUUID());
        var refundId = insertTransaction(accountId, "10.00", "REFUND", "CREDIT", "POSTED", LocalDate.now());

        assertThatThrownBy(() -> execute(
                "UPDATE ledger.transactions SET linked_transaction_id = ? WHERE transaction_id = ?",
                UUID.randomUUID(), refundId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("foreign key");
    }

    @Test
    @DisplayName("deleting the linked transaction clears the link instead of deleting the refund")
    void deletingLinkedTransactionClearsTheLink() throws SQLException {
        var accountId = insertAccount(UUID.randomUUID());
        var expenseId = insertTransaction(accountId, "-10.00", "EXPENSE", "DEBIT", "POSTED", LocalDate.now());
        var refundId = insertTransaction(accountId, "10.00", "REFUND", "CREDIT", "POSTED", LocalDate.now());
        execute("UPDATE ledger.transactions SET linked_transaction_id = ? WHERE transaction_id = ?",
                expenseId, refundId);

        execute("DELETE FROM ledger.transactions WHERE transaction_id = ?", expenseId);

        assertThat(readColumn(refundId, "transaction_id")).isEqualTo(refundId.toString());
        assertThat(readColumn(refundId, "linked_transaction_id")).isNull();
    }

    // -------------------------------------------------------------- repository (TXT-01)

    @Test
    @DisplayName("save() persists and reads back type, direction, currency, isRecurring and linkedTransactionId")
    void saveRoundTripsTheNewFields() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccount(userId);
        var expenseId = insertTransaction(accountId, "-25.00", "EXPENSE", "DEBIT", "POSTED", LocalDate.now());
        UserContextHolder.set(userId);

        var saved = transactionRepository.save(new Transaction(
                null, accountId, null, null, null,
                new BigDecimal("25.00"), "Store", "Shopping", null, List.of(),
                LocalDate.now(), Transaction.TransactionSource.MANUAL_ENTRY,
                Transaction.TransactionType.REFUND, Transaction.TransactionStatus.POSTED,
                false, true, null, null,
                Transaction.TransactionDirection.CREDIT, "CAD", true, expenseId));

        assertThat(saved.type()).isEqualTo(Transaction.TransactionType.REFUND);
        assertThat(saved.direction()).isEqualTo(Transaction.TransactionDirection.CREDIT);
        assertThat(saved.currency()).isEqualTo("CAD");
        assertThat(saved.isRecurring()).isTrue();
        assertThat(saved.linkedTransactionId()).isEqualTo(expenseId);
    }

    @Test
    @DisplayName("bulkInsertIgnoringDuplicates() persists each row's direction and currency")
    void bulkInsertPersistsDirectionAndCurrency() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccount(userId);
        var statementId = insertStatement(accountId);
        UserContextHolder.set(userId);

        transactionRepository.bulkInsertIgnoringDuplicates(statementId, List.of(new Transaction(
                null, accountId, statementId, null, null,
                new BigDecimal("900.00"), "Employer", "Income", null, List.of(),
                LocalDate.now(), Transaction.TransactionSource.STATEMENT_UPLOAD,
                Transaction.TransactionType.INCOME, Transaction.TransactionStatus.PENDING,
                false, false, null, "f".repeat(64),
                Transaction.TransactionDirection.CREDIT, "EUR", null, null)));

        var rows = query("SELECT type, direction, currency FROM ledger.transactions WHERE statement_id = ?",
                statementId);
        assertThat(rows).containsExactly(List.of("INCOME", "CREDIT", "EUR"));
    }

    // ------------------------------------------------------- dashboard totals (TXT-02)

    @Test
    @DisplayName("monthly income counts only INCOME — refunds, transfers and adjustments are not income")
    void monthlyIncomeCountsOnlyIncome() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccount(userId);
        var today = LocalDate.now();
        insertTransaction(accountId, "1000.00", "INCOME", "CREDIT", "POSTED", today);
        insertTransaction(accountId, "50.00", "REFUND", "CREDIT", "POSTED", today);
        insertTransaction(accountId, "300.00", "TRANSFER", "CREDIT", "POSTED", today);
        insertTransaction(accountId, "5.00", "ADJUSTMENT", "CREDIT", "POSTED", today);
        UserContextHolder.set(userId);

        var income = transactionRepository.sumMonthlyIncome(userId, monthStart(), monthEnd());

        assertThat(income).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("monthly expenses are EXPENSE minus REFUND; transfers, income and adjustments are excluded")
    void monthlyExpensesNetRefundsAndExcludeNonSpending() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccount(userId);
        var today = LocalDate.now();
        insertTransaction(accountId, "-150.00", "EXPENSE", "DEBIT", "POSTED", today);
        insertTransaction(accountId, "50.00", "REFUND", "CREDIT", "POSTED", today);
        insertTransaction(accountId, "-400.00", "TRANSFER", "DEBIT", "POSTED", today);
        insertTransaction(accountId, "1000.00", "INCOME", "CREDIT", "POSTED", today);
        insertTransaction(accountId, "-7.00", "ADJUSTMENT", "DEBIT", "POSTED", today);
        UserContextHolder.set(userId);

        var expenses = transactionRepository.sumMonthlyExpenses(userId, monthStart(), monthEnd());

        assertThat(expenses).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("monthly expenses go negative when refunds exceed expenses")
    void monthlyExpensesCanBeNegative() throws SQLException {
        var userId = UUID.randomUUID();
        var accountId = insertAccount(userId);
        var today = LocalDate.now();
        insertTransaction(accountId, "-20.00", "EXPENSE", "DEBIT", "POSTED", today);
        insertTransaction(accountId, "50.00", "REFUND", "CREDIT", "POSTED", today);
        UserContextHolder.set(userId);

        var expenses = transactionRepository.sumMonthlyExpenses(userId, monthStart(), monthEnd());

        assertThat(expenses).isEqualByComparingTo("-30.00");
    }

    // ------------------------------------------------------------------------ helpers

    private static LocalDate monthStart() {
        return LocalDate.now().withDayOfMonth(1);
    }

    private static LocalDate monthEnd() {
        return LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
    }

    private Connection superuser() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void execute(String sql, Object... params) throws SQLException {
        try (Connection conn = superuser(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }

    private List<List<String>> query(String sql, Object... params) throws SQLException {
        try (Connection conn = superuser(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                var rows = new java.util.ArrayList<List<String>>();
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    var row = new java.util.ArrayList<String>();
                    for (int c = 1; c <= columns; c++) {
                        row.add(rs.getString(c));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private String readColumn(UUID transactionId, String column) throws SQLException {
        var rows = query("SELECT " + column + " FROM ledger.transactions WHERE transaction_id = ?", transactionId);
        return rows.isEmpty() ? null : rows.get(0).get(0);
    }

    private UUID insertAccount(UUID userId) throws SQLException {
        var accountId = UUID.randomUUID();
        execute("""
                INSERT INTO ledger.accounts (account_id, user_id, account_name, account_type, sync_mode)
                VALUES (?, ?, 'Test Account', 'CHECKING', 'MANUAL')
                """, accountId, userId);
        return accountId;
    }

    private UUID insertStatement(UUID accountId) throws SQLException {
        var statementId = UUID.randomUUID();
        execute("""
                INSERT INTO ledger.statements
                    (statement_id, account_id, s3_object_key, status, source_format, opening_date, closing_date)
                VALUES (?, ?, 'statements/x/y/z.csv', 'PROCESSING', 'CSV', DATE '2026-08-03', DATE '2026-08-31')
                """, statementId, accountId);
        return statementId;
    }

    private UUID insertTransaction(UUID accountId, String amount, String type, String direction,
                                   String status, LocalDate txDate) throws SQLException {
        var transactionId = UUID.randomUUID();
        execute("""
                INSERT INTO ledger.transactions
                    (transaction_id, account_id, amount, merchant, category, tx_date, source, type, direction, status)
                VALUES (?, ?, ?, 'Test Merchant', 'Shopping', ?, 'MANUAL_ENTRY', ?, ?, ?)
                """, transactionId, accountId, new BigDecimal(amount), txDate, type, direction, status);
        return transactionId;
    }
}
