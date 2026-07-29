package com.fintracker.ledger.transaction.service;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.service.StatementService;
import com.fintracker.ledger.transaction.dto.ManualTransactionRequest;
import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.repository.TransactionRepository;
import com.fintracker.ledger.transaction.service.TransactionService;
import com.fintracker.ledger.transaction.service.impl.TransactionServiceImpl;
import com.fintracker.ledger.transaction.exception.IllegalStateTransitionException;
import com.fintracker.ledger.transaction.exception.SplitAmountMismatchException;
import com.fintracker.ledger.transaction.exception.TooManyTagsException;
import com.fintracker.ledger.transaction.exception.TransactionNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Coverage map to docs/fintracker-ledger-doc/ledger-spec, Module 2 (Transactions Management),
 * REQ-2.2 "Execute Row-Level and Bulk Actions":
 *
 *   - "Inline Row Modification" (update category/amount) — FAIL-TO-PASS. TransactionService now
 *     declares updateCategory(...)/updateAmount(...), but TransactionServiceImpl's implementations
 *     are stubs that throw UnsupportedOperationException (see UpdateCategory/UpdateAmount nested
 *     classes below). These tests describe the intended behavior and are expected to fail until a
 *     real implementation replaces the stubs. updateAmount additionally needs a new
 *     TransactionRepository.updateAmount(...) method — none exists yet.
 *   - "Tag Array Appending" — fully implemented and covered by the AppendTags nested class below:
 *     lowercase normalization, dedup against existing tags (case-insensitive) and within the same
 *     request, and the 10-tags-per-transaction limit (TooManyTagsException). Character/length/blank
 *     validation lives at the DTO layer (AppendTagsRequest), not tested here.
 *   - "Transaction Splitting" — covered by the SplitTransaction nested class below, including the
 *     parent-hiding-once-split business constraint (see JooqTransactionRepositoryIT for the
 *     database-level proof — that part can't be verified with a mocked repository).
 *   - "Spending Formula Exclusion" — covered by the ToggleExclude nested class below (previously
 *     had zero tests despite toggleExclude() being fully implemented).
 *   - "Status Promotion" (single + bulk approval) — single-approve covered by ApproveTransaction;
 *     bulk path covered by the new BulkApprove nested class below (previously had zero tests).
 *
 *   NFR "Atomic Integrity" (split/update must run in an ACID-isolated transaction) is NOT covered
 *   by this suite. Transaction boundaries are a Spring @Transactional proxy concern — invisible to
 *   a Mockito-mocked repository, since the mock never touches a real connection/transaction manager.
 *   Verifying this needs either a reflection check for @Transactional on the relevant methods, or a
 *   Testcontainers-backed integration test that forces a mid-split failure and asserts nothing persisted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService Unit Tests")
class TransactionServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private StatementService statementService;
    @Mock private AccountRepository accountRepository;

    private TransactionService transactionService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionServiceImpl(transactionRepository, statementService, accountRepository);
        userId = UUID.randomUUID();
    }

    private Transaction pendingTransaction(UUID id, UUID statementId) {
        return new Transaction(id, UUID.randomUUID(), statementId, null, null,
                new BigDecimal("-100.00"), "Merchant", "Groceries", "Description", List.of(),
                LocalDate.now(), Transaction.TransactionSource.STATEMENT_UPLOAD,
                Transaction.TransactionType.PURCHASE, Transaction.TransactionStatus.PENDING,
                false, false, null);
    }

    private Transaction transactionWithTags(UUID id, List<String> tags) {
        return new Transaction(id, UUID.randomUUID(), null, null, null,
                new BigDecimal("-100.00"), "Merchant", "Groceries", "Description", tags,
                LocalDate.now(), Transaction.TransactionSource.STATEMENT_UPLOAD,
                Transaction.TransactionType.PURCHASE, Transaction.TransactionStatus.PENDING,
                false, false, null);
    }

    private Transaction postedTransaction(UUID id) {
        return new Transaction(id, UUID.randomUUID(), null, null, null,
                new BigDecimal("-50.00"), "Shop", "Dining", "Description", List.of(),
                LocalDate.now(), Transaction.TransactionSource.MANUAL_ENTRY,
                Transaction.TransactionType.PURCHASE, Transaction.TransactionStatus.POSTED,
                false, true, null);
    }

    // REQ-2.2 "Status Promotion" (single-transaction path): status column transitions from
    // 'PENDING' to 'POSTED' upon explicit user verification. See BulkApprove below for
    // the batch-approval path.
    @Nested
    @DisplayName("approveTransaction()")
    class ApproveTransaction {

        @Test
        @DisplayName("should approve a PENDING transaction")
        void shouldApprovePendingTransaction() {
            var txId = UUID.randomUUID();
            var statementId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(pendingTransaction(txId, statementId)));
            when(transactionRepository.countPendingByStatementId(statementId)).thenReturn(0);

            transactionService.approveTransaction(txId, userId);

            verify(transactionRepository).updateStatus(txId, Transaction.TransactionStatus.POSTED);
        }

        @Test
        @DisplayName("should mark statement COMPLETED when last pending transaction is approved")
        void shouldCompleteStatementWhenLastTransactionApproved() {
            var txId = UUID.randomUUID();
            var statementId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(pendingTransaction(txId, statementId)));
            when(transactionRepository.countPendingByStatementId(statementId)).thenReturn(0);

            transactionService.approveTransaction(txId, userId);

            verify(statementService).updateStatus(statementId, Statement.StatementStatus.COMPLETED);
        }

        @Test
        @DisplayName("should NOT complete statement when other pending transactions remain")
        void shouldNotCompleteStatementWhenOthersPending() {
            var txId = UUID.randomUUID();
            var statementId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(pendingTransaction(txId, statementId)));
            when(transactionRepository.countPendingByStatementId(statementId)).thenReturn(3);

            transactionService.approveTransaction(txId, userId);

            verify(statementService, never()).updateStatus(any(), any());
        }

        @Test
        @DisplayName("should throw when approving an already POSTED transaction")
        void shouldThrowWhenApprovingAlreadyPosted() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(postedTransaction(txId)));

            assertThatThrownBy(() -> transactionService.approveTransaction(txId, userId))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        @DisplayName("should throw TransactionNotFoundException when transaction does not belong to the user")
        void shouldThrowWhenTransactionBelongsToDifferentUser() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.approveTransaction(txId, userId))
                    .isInstanceOf(TransactionNotFoundException.class);

            verify(transactionRepository, never()).updateStatus(any(), any());
        }
    }

    // REQ-2.2 "Status Promotion" (batch path): "upon ... batch approval execution." bulkApprove()
    // is currently a plain loop over approveTransaction() per id — no partial-failure handling,
    // no transactional wrapping, and no per-item result reporting. These tests document that as the
    // actual current contract (all-or-nothing loop, first failure aborts the remainder) rather than
    // an idealized one; if the intended behavior changes (e.g. best-effort with a per-id result list),
    // these tests should change with it.
    @Nested
    @DisplayName("bulkApprove()")
    class BulkApprove {

        @Test
        @DisplayName("REQ-2.2 Status Promotion (bulk): should approve every transaction in the batch")
        void shouldApproveAllTransactionsInBatch() {
            var id1 = UUID.randomUUID();
            var id2 = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(id1, userId))
                    .thenReturn(Optional.of(pendingTransaction(id1, null)));
            when(transactionRepository.findByIdAndUserId(id2, userId))
                    .thenReturn(Optional.of(pendingTransaction(id2, null)));

            transactionService.bulkApprove(List.of(id1, id2), userId);

            verify(transactionRepository).updateStatus(id1, Transaction.TransactionStatus.POSTED);
            verify(transactionRepository).updateStatus(id2, Transaction.TransactionStatus.POSTED);
        }

        @Test
        @DisplayName("REQ-2.2 Status Promotion (bulk): a mid-batch failure aborts remaining approvals "
                + "and leaves earlier ones already committed (current all-or-nothing loop behavior)")
        void shouldAbortRemainingApprovalsOnMidBatchFailure() {
            var okId = UUID.randomUUID();
            var alreadyPostedId = UUID.randomUUID();
            var neverReachedId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(okId, userId))
                    .thenReturn(Optional.of(pendingTransaction(okId, null)));
            when(transactionRepository.findByIdAndUserId(alreadyPostedId, userId))
                    .thenReturn(Optional.of(postedTransaction(alreadyPostedId)));

            assertThatThrownBy(() -> transactionService.bulkApprove(
                    List.of(okId, alreadyPostedId, neverReachedId), userId))
                    .isInstanceOf(IllegalStateTransitionException.class);

            verify(transactionRepository).updateStatus(okId, Transaction.TransactionStatus.POSTED);
            verify(transactionRepository, never()).findByIdAndUserId(neverReachedId, userId);
        }
    }

    // REQ-2.2 "Spending Formula Exclusion": toggles is_excluded to remove/restore a record from
    // downstream budget pacing computations without deleting the underlying row.
    @Nested
    @DisplayName("toggleExclude()")
    class ToggleExclude {

        @Test
        @DisplayName("REQ-2.2 Spending Formula Exclusion: should exclude a transaction owned by the user")
        void shouldExcludeOwnedTransaction() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(pendingTransaction(txId, null)));

            transactionService.toggleExclude(txId, true, userId);

            verify(transactionRepository).toggleExcluded(txId, true);
        }

        @Test
        @DisplayName("REQ-2.2 Spending Formula Exclusion: should re-include a previously excluded transaction")
        void shouldIncludeOwnedTransaction() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(pendingTransaction(txId, null)));

            transactionService.toggleExclude(txId, false, userId);

            verify(transactionRepository).toggleExcluded(txId, false);
        }

        @Test
        @DisplayName("REQ-2.2 Spending Formula Exclusion: should throw TransactionNotFoundException "
                + "when transaction does not belong to the user")
        void shouldThrowWhenTransactionBelongsToDifferentUser() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.toggleExclude(txId, true, userId))
                    .isInstanceOf(TransactionNotFoundException.class);

            verify(transactionRepository, never()).toggleExcluded(any(), anyBoolean());
        }
    }

    // REQ-2.2 "Transaction Splitting": supports split-transaction behavior via parent_transaction_id.
    @Nested
    @DisplayName("splitTransaction()")
    class SplitTransaction {

        @Test
        @DisplayName("REQ-2.2 Transaction Splitting: should reject splitting an already-POSTED transaction")
        void shouldRejectSplittingPostedTransaction() {
            var parentId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(parentId, userId))
                    .thenReturn(Optional.of(postedTransaction(parentId)));

            var splits = List.of(new TransactionService.SplitRequest(new BigDecimal("50.00"), "Groceries"));

            assertThatThrownBy(() -> transactionService.splitTransaction(parentId, splits, userId))
                    .isInstanceOf(IllegalStateTransitionException.class);

            verify(transactionRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("should split parent into children when amounts match exactly")
        void shouldSplitSuccessfully() {
            var parentId = UUID.randomUUID();
            var parent = pendingTransaction(parentId, null);
            when(transactionRepository.findByIdAndUserId(parentId, userId)).thenReturn(Optional.of(parent));
            when(transactionRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

            var splits = List.of(
                    new TransactionService.SplitRequest(new BigDecimal("60.00"), "Groceries"),
                    new TransactionService.SplitRequest(new BigDecimal("40.00"), "Dining"));

            var result = transactionService.splitTransaction(parentId, splits, userId);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("should throw SplitAmountMismatchException when split totals diverge")
        void shouldThrowOnAmountMismatch() {
            var parentId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(parentId, userId))
                    .thenReturn(Optional.of(pendingTransaction(parentId, null)));

            var badSplits = List.of(
                    new TransactionService.SplitRequest(new BigDecimal("50.00"), "Groceries"),
                    new TransactionService.SplitRequest(new BigDecimal("30.00"), "Dining"));

            assertThatThrownBy(() -> transactionService.splitTransaction(parentId, badSplits, userId))
                    .isInstanceOf(SplitAmountMismatchException.class);
        }

        @Test
        @DisplayName("should throw TransactionNotFoundException when parent does not belong to the user")
        void shouldThrowWhenParentBelongsToDifferentUser() {
            var parentId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(parentId, userId))
                    .thenReturn(Optional.empty());

            var splits = List.of(new TransactionService.SplitRequest(new BigDecimal("100.00"), "Groceries"));

            assertThatThrownBy(() -> transactionService.splitTransaction(parentId, splits, userId))
                    .isInstanceOf(TransactionNotFoundException.class);

            verify(transactionRepository, never()).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("deleteManualTransaction()")
    class DeleteManualTransaction {

        @Test
        @DisplayName("should reject deletion of a non-manual transaction")
        void shouldRejectDeletionOfNonManual() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(pendingTransaction(txId, null)));

            assertThatThrownBy(() -> transactionService.deleteManualTransaction(txId, userId))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("Only manual transactions");
        }

        @Test
        @DisplayName("should throw TransactionNotFoundException when transaction does not belong to the user")
        void shouldThrowWhenTransactionBelongsToDifferentUser() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.deleteManualTransaction(txId, userId))
                    .isInstanceOf(TransactionNotFoundException.class);

            verify(transactionRepository, never()).deleteManualTransaction(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // FAIL-TO-PASS: the three nested classes below describe intended behavior for REQ-2.2
    // "Inline Row Modification" and "Tag Array Appending", neither of which has a real
    // implementation yet — TransactionServiceImpl's updateCategory/updateAmount/appendTags all
    // currently throw UnsupportedOperationException. Every test here is expected to FAIL against
    // the current codebase; they should start passing once a real implementation is written
    // (ownership check via findByIdAndUserId, then delegate to the repository, matching the
    // pattern already used by toggleExclude/approveTransaction/deleteManualTransaction above).
    // ─────────────────────────────────────────────────────────────────────────────────────────

    // REQ-2.2 "Inline Row Modification" (category edit): "update existing database records ...
    // to alter metadata elements including categories."
    @Nested
    @DisplayName("updateCategory()")
    class UpdateCategory {

        @Test
        @DisplayName("REQ-2.2 Inline Row Modification: should persist the new category "
                + "for a transaction owned by the user")
        void shouldUpdateCategoryOfOwnedTransaction() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(pendingTransaction(txId, null)));

            transactionService.updateCategory(txId, "Dining", userId);

            verify(transactionRepository).updateCategory(txId, "Dining");
        }

        @Test
        @DisplayName("REQ-2.2 Inline Row Modification: should throw TransactionNotFoundException "
                + "when transaction does not belong to the user")
        void shouldThrowWhenTransactionBelongsToDifferentUser() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.updateCategory(txId, "Dining", userId))
                    .isInstanceOf(TransactionNotFoundException.class);

            verify(transactionRepository, never()).updateCategory(any(), any());
        }
    }

    // REQ-2.2 "Inline Row Modification" (amount edit): "... including categories, transaction
    // amounts." Signed amounts are the established convention in this codebase (see
    // pendingTransaction() using -100.00 for an ordinary expense), so only exact zero is rejected
    // here, not negative values.
    @Nested
    @DisplayName("updateAmount()")
    class UpdateAmount {

        @Test
        @DisplayName("REQ-2.2 Inline Row Modification: should accept a valid signed amount "
                + "for a transaction owned by the user")
        void shouldAcceptValidAmountUpdateForOwnedTransaction() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(pendingTransaction(txId, null)));

            assertThatCode(() -> transactionService.updateAmount(txId, new BigDecimal("-75.50"), userId))
                    .doesNotThrowAnyException();

            // TransactionRepository has no updateAmount(...) method yet. Once a real implementation
            // adds one, extend this test with:
            //   verify(transactionRepository).updateAmount(txId, new BigDecimal("-75.50"));
        }

        @Test
        @DisplayName("REQ-2.2 Inline Row Modification: should reject a zero amount")
        void shouldRejectZeroAmount() {
            // Inferred from the DB CHECK (amount != 0) constraint on ledger.transactions
            // (V1__Initial_Schema.sql) — not explicitly stated in REQ-2.2 itself, flagging as an
            // assumption worth confirming rather than a hard requirement. The service should fail
            // fast with a clear exception instead of letting a doomed write reach the database as
            // a raw constraint violation.
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(pendingTransaction(txId, null)));

            assertThatThrownBy(() -> transactionService.updateAmount(txId, BigDecimal.ZERO, userId))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("REQ-2.2 Inline Row Modification: should throw TransactionNotFoundException "
                + "when transaction does not belong to the user")
        void shouldThrowWhenTransactionBelongsToDifferentUser() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.updateAmount(txId, new BigDecimal("10.00"), userId))
                    .isInstanceOf(TransactionNotFoundException.class);
        }
    }

    // REQ-2.2 "Tag Array Appending": user-scoped free-text tags (no global registry). Character/
    // length/blank validation lives at the DTO layer (AppendTagsRequest's Bean Validation); this
    // service layer owns normalization (lowercase, for case-insensitive dedup/search) and the
    // transformation logic — dedup against existing tags and within the request itself, plus the
    // max-10-tags-per-transaction business rule.
    @Nested
    @DisplayName("appendTags()")
    class AppendTags {

        @Test
        @DisplayName("REQ-2.2 Tag Array Appending: should append new tags to a transaction "
                + "owned by the user")
        void shouldAppendTagsToOwnedTransaction() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(pendingTransaction(txId, null)));

            transactionService.appendTags(txId, List.of("recurring", "reviewed"), userId);

            // Behavior under test: the new tags are appended to whatever the row already has, not
            // used to replace the full array — TransactionRepository.appendTags(...) is expected to
            // perform the merge at the persistence layer (e.g. array concatenation in SQL), not the
            // service. This test only verifies the service delegates the correct new values through.
            verify(transactionRepository).appendTags(txId, List.of("recurring", "reviewed"));
        }

        @Test
        @DisplayName("REQ-2.2 Tag Array Appending: should throw TransactionNotFoundException "
                + "when transaction does not belong to the user")
        void shouldThrowWhenTransactionBelongsToDifferentUser() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.appendTags(txId, List.of("recurring"), userId))
                    .isInstanceOf(TransactionNotFoundException.class);

            verify(transactionRepository, never()).appendTags(any(), anyList());
        }

        @Test
        @DisplayName("REQ-2.2 Tag Array Appending: should normalize tags to lowercase for "
                + "case-insensitive dedup/search")
        void shouldNormalizeTagsToLowercase() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(pendingTransaction(txId, null)));

            transactionService.appendTags(txId, List.of("URGENT"), userId);

            verify(transactionRepository).appendTags(txId, List.of("urgent"));
        }

        @Test
        @DisplayName("REQ-2.2 Tag Array Appending: should not re-append a tag the transaction "
                + "already has, case-insensitively")
        void shouldNotAppendDuplicateOfExistingTag() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(transactionWithTags(txId, List.of("groceries"))));

            transactionService.appendTags(txId, List.of("Groceries"), userId);

            verify(transactionRepository, never()).appendTags(any(), anyList());
        }

        @Test
        @DisplayName("REQ-2.2 Tag Array Appending: should dedup case-insensitive duplicates "
                + "within the same request")
        void shouldDedupWithinSameRequest() {
            var txId = UUID.randomUUID();
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(pendingTransaction(txId, null)));

            transactionService.appendTags(txId, List.of("Urgent", "urgent"), userId);

            verify(transactionRepository).appendTags(txId, List.of("urgent"));
        }

        @Test
        @DisplayName("REQ-2.2 Tag Array Appending: should allow reaching exactly the 10-tag limit")
        void shouldAllowReachingExactlyTheTagLimit() {
            var txId = UUID.randomUUID();
            var existing = List.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8");
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(transactionWithTags(txId, existing)));

            transactionService.appendTags(txId, List.of("t9", "t10"), userId);

            verify(transactionRepository).appendTags(txId, List.of("t9", "t10"));
        }

        @Test
        @DisplayName("REQ-2.2 Tag Array Appending: should throw TooManyTagsException when the "
                + "result would exceed 10 tags")
        void shouldRejectExceedingTagLimit() {
            var txId = UUID.randomUUID();
            var existing = List.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9");
            when(transactionRepository.findByIdAndUserId(txId, userId))
                    .thenReturn(Optional.of(transactionWithTags(txId, existing)));

            assertThatThrownBy(() -> transactionService.appendTags(txId, List.of("t10", "t11"), userId))
                    .isInstanceOf(TooManyTagsException.class);

            verify(transactionRepository, never()).appendTags(any(), anyList());
        }
    }

    // REQ-2.3.1 "Manual Row Insertion" — fully implemented in TransactionServiceImpl. Account
    // ownership is verified via AccountRepository.existsByIdAndUserId before any write.
    @Nested
    @DisplayName("createManualTransaction()")
    class CreateManualTransaction {

        @Test
        @DisplayName("REQ-2.3.1.C/D: should save with source=MANUAL_ENTRY, isManual=true, "
                + "status=POSTED regardless of request input — these three fields are fixed, not "
                + "user-selectable, per the spec's Business Constraints")
        void shouldSaveWithManualEntrySourceIsManualTrueAndPostedStatus() {
            var accountId = UUID.randomUUID();
            var request = new ManualTransactionRequest(accountId, new BigDecimal("-42.50"),
                    "Corner Store", "Groceries", List.of(), LocalDate.of(2026, 6, 1), "PURCHASE");
            when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(true);
            when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            var result = transactionService.createManualTransaction(request, userId);

            assertThat(result.source()).isEqualTo(Transaction.TransactionSource.MANUAL_ENTRY);
            assertThat(result.isManual()).isTrue();
            assertThat(result.status()).isEqualTo(Transaction.TransactionStatus.POSTED);

            var captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().source()).isEqualTo(Transaction.TransactionSource.MANUAL_ENTRY);
            assertThat(captor.getValue().isManual()).isTrue();
            assertThat(captor.getValue().status()).isEqualTo(Transaction.TransactionStatus.POSTED);
            assertThat(captor.getValue().accountId()).isEqualTo(accountId);
            assertThat(captor.getValue().amount()).isEqualByComparingTo("-42.50");
        }

        @Test
        @DisplayName("REQ-2.3.1.D: should default txDate to today when the request omits it")
        void shouldDefaultTxDateToTodayWhenNotProvided() {
            var request = new ManualTransactionRequest(UUID.randomUUID(), new BigDecimal("-10.00"),
                    "Corner Store", "Groceries", List.of(), null, "PURCHASE");
            when(accountRepository.existsByIdAndUserId(any(), eq(userId))).thenReturn(true);
            when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            var result = transactionService.createManualTransaction(request, userId);

            assertThat(result.txDate()).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("REQ-2.3.1.D: should preserve a user-supplied txDate instead of defaulting it")
        void shouldPreserveExplicitTxDate() {
            var explicitDate = LocalDate.of(2026, 3, 15);
            var request = new ManualTransactionRequest(UUID.randomUUID(), new BigDecimal("-10.00"),
                    "Corner Store", "Groceries", List.of(), explicitDate, "PURCHASE");
            when(accountRepository.existsByIdAndUserId(any(), eq(userId))).thenReturn(true);
            when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            var result = transactionService.createManualTransaction(request, userId);

            assertThat(result.txDate()).isEqualTo(explicitDate);
        }

        @Test
        @DisplayName("Inferred tenant-isolation constraint (REQ-1.1's spirit): should reject an "
                + "accountId the requesting user doesn't own. The spec doesn't explicitly say this "
                + "for REQ-2.3.1, but every other write path in this service (approve/split/delete) "
                + "enforces ownership via findByIdAndUserId before writing — silently accepting a "
                + "foreign accountId here would be a cross-tenant write hole inconsistent with the "
                + "rest of the service. Flagged separately in the audit report as a spec gap worth "
                + "closing explicitly.")
        void shouldRejectWhenAccountDoesNotBelongToUser() {
            var foreignAccountId = UUID.randomUUID();
            var request = new ManualTransactionRequest(foreignAccountId, new BigDecimal("-10.00"),
                    "Corner Store", "Groceries", List.of(), LocalDate.now(), "PURCHASE");

            assertThatThrownBy(() -> transactionService.createManualTransaction(request, userId))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(transactionRepository, never()).save(any());
        }
    }
}
