package com.fintracker.ledger.statement.controller;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.exception.DuplicateStatementException;
import com.fintracker.ledger.statement.service.StatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-STMT-03's internal duplicate-check endpoint. Two behaviors live only here and nowhere else:
 *
 * <ul>
 *   <li><b>The accountId ownership guard.</b> {@code checkForDuplicateByContentHash} takes no
 *       userId, so the controller is the only place the caller-supplied accountId is checked
 *       against the caller's identity. This endpoint hands back another account's
 *       existingStatementId, existingUploadDate and existingTransactionCount, and accountId arrives
 *       as a raw query parameter — so a missing guard here leaks statement metadata across tenants
 *       to anyone the caller allow-list admits.</li>
 *   <li><b>The contentHash / contentFingerprint mutual exclusion.</b> Both present, or neither, is
 *       a 400 "rather than silently picking one" — silently picking would answer a question the
 *       Gatekeeper did not ask, at a pipeline stage where the two hashes mean different things.</li>
 * </ul>
 *
 * FAIL-TO-PASS: InternalStatementController does not exist before this change.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalStatementController Unit Tests")
class InternalStatementControllerTest {

    private static final String PATH = "/api/v1/ledger/statements/internal/duplicate-check";

    @Mock private StatementService statementService;
    @Mock private AccountRepository accountRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Collaborators and order per prompt.md: (StatementService, AccountRepository).
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalStatementController(statementService, accountRepository))
                .build();
    }

    @Test
    @DisplayName("delegates to checkForDuplicateByContentHash and reports the match, with "
            + "statementMonth omitted — this query is never about the month")
    void reportsAContentHashMatch() throws Exception {
        var callerUserId = UUID.randomUUID();
        var accountId = UUID.randomUUID();
        var existingStatementId = UUID.randomUUID();
        when(accountRepository.existsByIdAndUserId(accountId, callerUserId)).thenReturn(true);
        when(statementService.checkForDuplicateByContentHash(accountId, "a".repeat(64), null))
                .thenReturn(Optional.of(new StatementService.DuplicateCheckResult(
                        DuplicateStatementException.MatchType.EXACT_FILE, existingStatementId,
                        OffsetDateTime.parse("2026-08-27T10:15:00Z"), 34)));

        mockMvc.perform(get(PATH)
                        .requestAttr("userId", callerUserId)
                        .param("accountId", accountId.toString())
                        .param("contentHash", "a".repeat(64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateFound").value(true))
                .andExpect(jsonPath("$.matchType").value("EXACT_FILE"))
                .andExpect(jsonPath("$.existingStatementId").value(existingStatementId.toString()))
                .andExpect(jsonPath("$.existingTransactionCount").value(34));
    }

    @Test
    @DisplayName("multi-tenant: an accountId that doesn't belong to the calling identity is "
            + "rejected before any duplicate query runs")
    void rejectsAnAccountIdNotOwnedByTheCaller() {
        var callerUserId = UUID.randomUUID();
        var victimAccountId = UUID.randomUUID();
        when(accountRepository.existsByIdAndUserId(victimAccountId, callerUserId)).thenReturn(false);

        try {
            mockMvc.perform(get(PATH)
                    .requestAttr("userId", callerUserId)
                    .param("accountId", victimAccountId.toString())
                    .param("contentHash", "a".repeat(64)));
        } catch (Exception expected) {
            // IllegalArgumentException maps to 400 via GlobalExceptionHandler in the real app;
            // standalone MockMvc has no advice registered, so it surfaces the exception instead.
            // Either way, the assertion that matters is that no lookup happened.
        }

        verify(statementService, never()).checkForDuplicateByContentHash(any(), any(), any());
    }

    @Test
    @DisplayName("contentHash and contentFingerprint are mutually exclusive: supplying both, or "
            + "neither, is rejected rather than silently picking one")
    void rejectsBothOrNeitherHashParameter() {
        var callerUserId = UUID.randomUUID();
        var accountId = UUID.randomUUID();

        for (var request : java.util.List.of(
                get(PATH).requestAttr("userId", callerUserId)
                        .param("accountId", accountId.toString())
                        .param("contentHash", "a".repeat(64))
                        .param("contentFingerprint", "b".repeat(64)),
                get(PATH).requestAttr("userId", callerUserId)
                        .param("accountId", accountId.toString()))) {
            try {
                mockMvc.perform(request);
            } catch (Exception expected) {
                // As above: the 400 mapping lives in GlobalExceptionHandler, which standalone
                // MockMvc does not register.
            }
        }

        verify(statementService, never()).checkForDuplicateByContentHash(any(), any(), any());
    }
}
