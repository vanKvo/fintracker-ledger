package com.fintracker.ledger.statement.service;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.dto.InitiateStatementUploadRequest;
import com.fintracker.ledger.statement.exception.DuplicateStatementException;
import com.fintracker.ledger.statement.exception.StatementNotFoundException;
import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.repository.StatementRepository;
import com.fintracker.ledger.statement.service.impl.StatementServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-STMT-05 — letting the user decide on a detected duplicate, Ledger half.
 *
 * <p>The spec resolves overwrite down to a single mechanism: the client re-submits the same
 * initiate-upload request with overwriteStatementId set, for both the up-front (REQ-STMT-03) and
 * mid-processing (REQ-STMT-04) cases. So the whole feature lives inside initiateUpload, and these
 * tests are about what must happen before the replacement row is created.
 *
 * <p>Whether the delete actually removes the old transactions is a database guarantee (V18's
 * ON DELETE CASCADE) that a mocked repository cannot see — proven in StatementOverwriteIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("REQ-STMT-05 overwrite a detected duplicate")
class StatementOverwriteTest {

    @Mock private StatementRepository statementRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private S3PresignService s3PresignService;

    private StatementServiceImpl statementService;
    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        statementService = new StatementServiceImpl(statementRepository, accountRepository, s3PresignService);
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        lenient().when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(true);
    }

    private InitiateStatementUploadRequest request(UUID overwriteStatementId) {
        return new InitiateStatementUploadRequest(
                accountId, "August statement", "CSV", "statement.csv", "chase",
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), "a".repeat(64), overwriteStatementId);
    }

    private Statement statementOwnedBy(UUID statementId, UUID ownerAccountId) {
        return new Statement(statementId, ownerAccountId, "statements/u/s/old.csv",
                LocalDate.of(2026, 9, 1), Statement.StatementStatus.COMPLETED, null,
                OffsetDateTime.parse("2026-08-27T10:15:00Z"), "CSV", "chase", "a".repeat(64), null,
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), 34, 0, 34);
    }

    @Test
    @DisplayName("the statement being replaced is deleted before the duplicate check runs, so the "
            + "row that would have matched is gone and the new upload proceeds")
    void theReplacedStatementIsDeletedBeforeTheDuplicateCheck() {
        var oldStatementId = UUID.randomUUID();
        when(statementRepository.findByIdAndUserId(oldStatementId, userId))
                .thenReturn(Optional.of(statementOwnedBy(oldStatementId, accountId)));
        // After the delete, the account no longer holds that file or that month.
        when(statementRepository.findByAccountIdAndContentHash(any(), any())).thenReturn(Optional.empty());
        when(statementRepository.findByAccountIdAndStatementMonth(any(), any())).thenReturn(Optional.empty());
        when(s3PresignService.presignStatementUpload(any(), any(), any(), any(), any()))
                .thenReturn(new S3PresignService.PresignedUpload("https://s3/put", "statements/u/s/new.csv"));
        when(statementRepository.insert(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> statementOwnedBy(inv.getArgument(0), accountId));

        statementService.initiateUpload(request(oldStatementId), userId);

        // Ordering is the substance of this test, not incidental: a duplicate check that ran
        // first would match the very statement being replaced and reject the overwrite outright.
        InOrder order = inOrder(statementRepository);
        order.verify(statementRepository).deleteByIdAndUserId(oldStatementId, userId);
        order.verify(statementRepository).findByAccountIdAndContentHash(accountId, "a".repeat(64));
        order.verify(statementRepository).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // Multi-tenant: the spec requires re-confirming ownership before overwriting, never assuming
    // it because a duplicate check pointed at the statement. Deleting on the strength of a
    // client-supplied id alone would let any authenticated user erase any statement in the system.
    @Test
    @DisplayName("multi-tenant: overwriting a statement that belongs to another user is reported "
            + "as not found, and nothing is deleted")
    @Tag("multi-tenant")
    void overwritingAnotherTenantsStatementIsRejectedAndDeletesNothing() {
        var foreignStatementId = UUID.randomUUID();
        when(statementRepository.findByIdAndUserId(foreignStatementId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> statementService.initiateUpload(request(foreignStatementId), userId))
                .isInstanceOf(StatementNotFoundException.class);

        verify(statementRepository, never()).deleteByIdAndUserId(any(), any());
        verify(statementRepository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // The statement is genuinely the caller's, just filed under a different account of theirs.
    // That is a malformed request rather than a permission problem, and it must not be allowed to
    // become a way to move a statement between accounts.
    @Test
    @DisplayName("overwriting a statement from a different account of the same user is rejected, "
            + "and nothing is deleted")
    @Tag("multi-tenant")
    void overwritingAStatementInAnotherAccountIsRejected() {
        var otherAccountId = UUID.randomUUID();
        var statementInOtherAccount = UUID.randomUUID();
        when(statementRepository.findByIdAndUserId(statementInOtherAccount, userId))
                .thenReturn(Optional.of(statementOwnedBy(statementInOtherAccount, otherAccountId)));

        assertThatThrownBy(() -> statementService.initiateUpload(request(statementInOtherAccount), userId))
                .isInstanceOf(IllegalArgumentException.class);

        verify(statementRepository, never()).deleteByIdAndUserId(any(), any());
        verify(statementRepository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("without overwriteStatementId nothing is deleted and a duplicate is still a "
            + "rejection — overwrite is opt-in, never the default")
    void withoutOverwriteADuplicateIsStillRejected() {
        when(statementRepository.findByAccountIdAndContentHash(accountId, "a".repeat(64)))
                .thenReturn(Optional.of(statementOwnedBy(UUID.randomUUID(), accountId)));

        assertThatThrownBy(() -> statementService.initiateUpload(request(null), userId))
                .isInstanceOf(DuplicateStatementException.class);

        verify(statementRepository, never()).deleteByIdAndUserId(any(), any());
    }

    // The ownership guard must come before the delete, not merely exist somewhere in the method.
    @Test
    @DisplayName("an account the user does not own is rejected before the overwrite is even "
            + "considered")
    @Tag("multi-tenant")
    void aForeignAccountIsRejectedBeforeTheOverwrite() {
        var foreignAccount = UUID.randomUUID();
        when(accountRepository.existsByIdAndUserId(foreignAccount, userId)).thenReturn(false);
        var req = new InitiateStatementUploadRequest(
                foreignAccount, null, "CSV", "statement.csv", "chase",
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), "a".repeat(64), UUID.randomUUID());

        assertThatThrownBy(() -> statementService.initiateUpload(req, userId))
                .isInstanceOf(IllegalArgumentException.class);

        verify(statementRepository, never()).findByIdAndUserId(any(), any());
        verify(statementRepository, never()).deleteByIdAndUserId(any(), any());
    }
}
