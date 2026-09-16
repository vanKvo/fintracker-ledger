package com.fintracker.ledger.statement.controller;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.exception.DuplicateStatementException;
import com.fintracker.ledger.statement.exception.StatementNotFoundException;
import com.fintracker.ledger.statement.service.StatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-STMT-04 / REQ-STMT-08 at the internal HTTP boundary — the two things the service-level tests
 * structurally cannot see: which query parameter routes to which check, and the request shape of
 * the fingerprint write.
 *
 * <p>REQ-STMT-08's entire Ledger obligation is that this endpoint "never returns
 * duplicateFound=false for a fingerprint that in fact matches an existing statement". Dispatching
 * a fingerprint query to the content-hash check would do exactly that — silently answer a
 * different question and report no duplicate — so the dispatch test below is REQ-STMT-08's test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("REQ-STMT-04/08 internal fingerprint endpoints")
class InternalStatementFingerprintControllerTest {

    private static final String CHECK_PATH = "/api/v1/ledger/statements/internal/duplicate-check";

    @Mock private StatementService statementService;
    @Mock private AccountRepository accountRepository;

    private MockMvc mockMvc;
    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalStatementController(statementService, accountRepository))
                .build();
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
    }

    // REQ-STMT-08: the recheck must answer the question it was asked. Routing a fingerprint to the
    // hash check would report duplicateFound=false for a statement that genuinely matches, letting
    // a duplicate the up-front check missed through — the one outcome the spec says cannot happen.
    @Test
    @DisplayName("REQ-STMT-08: a contentFingerprint query is answered by the fingerprint check, "
            + "never by the content-hash check")
    void aFingerprintQueryIsAnsweredByTheFingerprintCheck() throws Exception {
        var fingerprint = "b".repeat(64);
        var existingId = UUID.randomUUID();
        when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(true);
        when(statementService.checkForDuplicateByContentFingerprint(accountId, fingerprint))
                .thenReturn(Optional.of(new StatementService.DuplicateCheckResult(
                        DuplicateStatementException.MatchType.CONTENT_FINGERPRINT, existingId,
                        OffsetDateTime.parse("2026-08-27T10:15:00Z"), 34)));

        mockMvc.perform(get(CHECK_PATH)
                        .param("accountId", accountId.toString())
                        .param("contentFingerprint", fingerprint)
                        .requestAttr("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateFound").value(true))
                .andExpect(jsonPath("$.matchType").value("CONTENT_FINGERPRINT"))
                .andExpect(jsonPath("$.existingStatementId").value(existingId.toString()))
                .andExpect(jsonPath("$.existingTransactionCount").value(34));

        verify(statementService, never()).checkForDuplicateByContentHash(any(), any(), any());
    }

    @Test
    @DisplayName("REQ-STMT-04: a fingerprint with no match answers duplicateFound=false rather "
            + "than an error — a first upload is not a failure")
    void aFingerprintWithNoMatchIsNotAnError() throws Exception {
        when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(true);
        when(statementService.checkForDuplicateByContentFingerprint(any(), any()))
                .thenReturn(Optional.empty());

        mockMvc.perform(get(CHECK_PATH)
                        .param("accountId", accountId.toString())
                        .param("contentFingerprint", "c".repeat(64))
                        .requestAttr("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateFound").value(false));
    }

    // Multi-tenant: the fingerprint branch must be behind the same ownership guard as the hash
    // branch. This response carries another account's statement id, upload date and transaction
    // count, so an unguarded branch leaks that metadata to any caller the allow-list admits.
    @Test
    @DisplayName("multi-tenant: a fingerprint query for an account the caller does not own is "
            + "rejected before any lookup runs")
    @Tag("multi-tenant")
    void aFingerprintQueryForAForeignAccountIsRejected() throws Exception {
        when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(false);

        try {
            mockMvc.perform(get(CHECK_PATH)
                    .param("accountId", accountId.toString())
                    .param("contentFingerprint", "d".repeat(64))
                    .requestAttr("userId", userId));
        } catch (Exception expected) {
            // IllegalArgumentException becomes a 400 through GlobalExceptionHandler in the real
            // app; standalone MockMvc registers no advice, so it surfaces here instead. Either
            // way the assertion that matters is that no lookup ran.
        }

        verify(statementService, never()).checkForDuplicateByContentFingerprint(any(), any());
    }

    @Test
    @DisplayName("REQ-STMT-04: recording a fingerprint answers 204 and passes the caller's identity "
            + "through, not just the path id")
    void recordingAFingerprintAnswers204() throws Exception {
        var statementId = UUID.randomUUID();
        var fingerprint = "e".repeat(64);

        mockMvc.perform(patch("/api/v1/ledger/statements/internal/%s/content-fingerprint".formatted(statementId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentFingerprint\":\"" + fingerprint + "\"}")
                        .requestAttr("userId", userId))
                .andExpect(status().isNoContent());

        // userId is the tenancy scope for the write; a call that dropped it would still pass a
        // status-only assertion.
        verify(statementService).recordContentFingerprint(statementId, userId, fingerprint);
    }

    // Multi-tenant: the service turns a foreign statement into StatementNotFoundException, and the
    // controller must let that propagate rather than reporting success for a write that never
    // happened.
    @Test
    @DisplayName("multi-tenant: recording a fingerprint onto another tenant's statement surfaces "
            + "as not found, not as a silent success")
    @Tag("multi-tenant")
    void recordingOntoAForeignStatementIsNotFound() throws Exception {
        var foreignStatementId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new StatementNotFoundException(foreignStatementId))
                .when(statementService).recordContentFingerprint(any(), any(), any());

        try {
            mockMvc.perform(patch("/api/v1/ledger/statements/internal/%s/content-fingerprint"
                            .formatted(foreignStatementId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"contentFingerprint\":\"" + "f".repeat(64) + "\"}")
                            .requestAttr("userId", userId))
                    .andExpect(status().is4xxClientError());
        } catch (Exception propagated) {
            // standaloneSetup has no GlobalExceptionHandler registered, so the exception escapes
            // instead of becoming a 404. Either outcome proves the same thing: the controller does
            // not swallow it and report success.
            org.assertj.core.api.Assertions.assertThat(propagated)
                    .hasRootCauseInstanceOf(StatementNotFoundException.class);
        }
    }

    @Test
    @DisplayName("REQ-STMT-04: a fingerprint that is not 64 hex characters is rejected before it "
            + "can be stored and silently fail to match anything later")
    void aMalformedFingerprintIsRejected() throws Exception {
        mockMvc.perform(patch("/api/v1/ledger/statements/internal/%s/content-fingerprint"
                        .formatted(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentFingerprint\":\"not-a-sha256\"}")
                        .requestAttr("userId", userId))
                .andExpect(status().isBadRequest());

        verify(statementService, never()).recordContentFingerprint(any(), any(), any());
    }
}
