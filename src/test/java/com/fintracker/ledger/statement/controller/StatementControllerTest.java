package com.fintracker.ledger.statement.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintracker.ledger.statement.dto.InitiateStatementUploadRequest;
import com.fintracker.ledger.statement.dto.StatementUploadResponse;
import com.fintracker.ledger.statement.service.StatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-STMT-03: the user-facing initiate-upload route now answers 202 Accepted with the job shape
 * ({@code jobId, status, uploadUrl, s3ObjectKey}) rather than 201 Created with
 * {@code statementId/url}. Both the status code and the field names are part of the contract the
 * browser client codes against, and neither is observable from any service-level test.
 *
 * <p>Also pins that the accountId ownership check the service performs is fed the identity
 * UserContextFilter derived from {@code X-Internal-User-Id}, not anything in the request body —
 * the user-facing counterpart to InternalTransactionControllerTest.
 *
 * FAIL-TO-PASS: the response is 201 with the old field names before this change, and
 * InitiateStatementUploadRequest still carries statementMonth instead of openingDate/closingDate.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementController Unit Tests")
class StatementControllerTest {

    private static final String INITIATE_PATH = "/api/v1/ledger/statements/initiate-upload";

    @Mock private StatementService statementService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new StatementController(statementService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private String body(UUID accountId, String contentHash) {
        return """
                {"accountId":"%s","description":"August statement","sourceFormat":"CSV",
                 "fileName":"statement.csv","bankId":"chase",
                 "openingDate":"2026-08-03","closingDate":"2026-09-02",
                 "contentHash":"%s","overwriteStatementId":null}
                """.formatted(accountId, contentHash);
    }

    @Test
    @DisplayName("REQ-STMT-03: a successful initiate-upload answers 202 Accepted with "
            + "jobId/status/uploadUrl/s3ObjectKey")
    void successfulUploadReturns202WithTheJobShape() throws Exception {
        var jobId = UUID.randomUUID();
        when(statementService.initiateUpload(any(), any()))
                .thenReturn(new StatementUploadResponse(
                        jobId, "PROCESSING", "https://s3/upload", "statements/a/b/statement.csv"));

        mockMvc.perform(post(INITIATE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", UUID.randomUUID())
                        .content(body(UUID.randomUUID(), "a".repeat(64))))
                // 202, not 201: the upload is accepted for asynchronous processing, not completed.
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.uploadUrl").value("https://s3/upload"))
                .andExpect(jsonPath("$.s3ObjectKey").value("statements/a/b/statement.csv"));
    }

    @Test
    @DisplayName("multi-tenant: the upload is attributed to the X-Internal-User-Id-derived request "
            + "attribute, and the parsed request carries the client's dates and contentHash intact")
    void attributesTheUploadToTheRequestAttributeUserId() throws Exception {
        var callerUserId = UUID.randomUUID();
        var accountId = UUID.randomUUID();
        when(statementService.initiateUpload(any(), eq(callerUserId)))
                .thenReturn(new StatementUploadResponse(
                        UUID.randomUUID(), "PROCESSING", "https://s3/upload", "key"));

        mockMvc.perform(post(INITIATE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", callerUserId)
                        .content(body(accountId, "a".repeat(64))))
                .andExpect(status().isAccepted());

        var captor = ArgumentCaptor.forClass(InitiateStatementUploadRequest.class);
        verify(statementService).initiateUpload(captor.capture(), eq(callerUserId));
        assertThat(captor.getValue().accountId()).isEqualTo(accountId);
        assertThat(captor.getValue().contentHash()).isEqualTo("a".repeat(64));
        assertThat(captor.getValue().openingDate()).isNotNull();
        assertThat(captor.getValue().closingDate()).isNotNull();
    }
}
