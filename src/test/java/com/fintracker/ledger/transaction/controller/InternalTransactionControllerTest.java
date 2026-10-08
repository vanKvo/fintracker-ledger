package com.fintracker.ledger.transaction.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintracker.ledger.transaction.dto.BulkCreateTransactionsResponse;
import com.fintracker.ledger.transaction.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-STMT-02, the "Multi-tenant security" note: the internal bulk-create endpoint must take the
 * caller's identity from the request attribute UserContextFilter populates from the
 * X-Internal-User-Id header, and must never take it from the request body.
 *
 * <p>Driven through standalone MockMvc rather than a full Spring context so it stays a fast unit
 * test: the point here is the controller's own parameter binding, not the filter chain (see
 * InternalEndpointSecurityIT for that).
 *
 * FAIL-TO-PASS: InternalTransactionController does not exist before this change.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalTransactionController Unit Tests")
class InternalTransactionControllerTest {

    private static final String BULK_PATH = "/api/v1/ledger/transactions/internal/bulk";

    @Mock private TransactionService transactionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Mirrors Spring Boot's own defaults (JSR-310 dates, unknown JSON properties ignored) so
        // the extra identity fields the "attacker" plants in the body below are dropped by Jackson
        // exactly as they would be at runtime, rather than short-circuiting into a 400.
        var objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalTransactionController(transactionService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private String body(UUID statementId, UUID forgedUserId, UUID forgedAccountId) {
        return """
                {"statementId":"%s","userId":"%s","accountId":"%s","transactions":[
                  {"date":"2026-08-15","merchant":"Corner Store","amount":-42.50,
                   "category":"Groceries","subCategory":null,"type":"EXPENSE","direction":"DEBIT",
                   "rowFingerprint":"%s"}]}
                """.formatted(statementId, forgedUserId, forgedAccountId, "a".repeat(64));
    }

    @Test
    @DisplayName("multi-tenant: the write is scoped to the X-Internal-User-Id-derived request "
            + "attribute, and identity fields planted in the JSON body are ignored")
    void scopesWriteToRequestAttributeUserIdNotBodyIdentity() throws Exception {
        var callerUserId = UUID.randomUUID();
        var forgedUserId = UUID.randomUUID();
        var forgedAccountId = UUID.randomUUID();
        var statementId = UUID.randomUUID();
        when(transactionService.bulkCreateFromStatement(eq(statementId), eq(callerUserId), anyList()))
                .thenReturn(new BulkCreateTransactionsResponse(1, 0, List.of()));

        mockMvc.perform(post(BULK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", callerUserId)
                        .content(body(statementId, forgedUserId, forgedAccountId)))
                .andExpect(status().isOk());

        verify(transactionService).bulkCreateFromStatement(eq(statementId), eq(callerUserId), anyList());
        verify(transactionService, never())
                .bulkCreateFromStatement(any(), eq(forgedUserId), anyList());
    }

    @Test
    @DisplayName("multi-tenant: a call that never went through the identity filter (no userId "
            + "request attribute) must not reach the service at all")
    void rejectsCallWithoutUserIdRequestAttribute() {
        var statementId = UUID.randomUUID();
        try {
            mockMvc.perform(post(BULK_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(statementId, UUID.randomUUID(), UUID.randomUUID())));
        } catch (Exception expected) {
            // A missing @RequestAttribute raises ServletRequestBindingException; standalone MockMvc
            // surfaces it rather than mapping it to a status. Either outcome is acceptable — what
            // matters is the assertion below.
        }

        verifyNoInteractions(transactionService);
    }
}
