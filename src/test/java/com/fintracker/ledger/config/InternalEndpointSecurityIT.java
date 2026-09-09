package com.fintracker.ledger.config;

import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-STMT-02 caller authentication, application half.
 *
 * <p>SigV4 signature verification itself is an edge concern — prompt.md is explicit that an invalid
 * or missing signature is rejected by API Gateway's {@code AWS_IAM} authorizer or the ALB signing
 * filter, with "nothing to implement in application code" — so nothing here asserts signature
 * validity. What IS application code is {@code InternalCallerFilter}: it reads the
 * {@code X-Internal-Caller-Arn} header the edge sets from the verified principal, and checks it
 * against {@code ledger.internal.allowed-caller-arns}. Under the test profile that list holds the
 * local development ARN below, and the filter itself is never disabled by profile — only the
 * contents of the list differ — so its fail-closed behavior is exercised here exactly as in
 * production.
 *
 * <p>The stakes: an internal route reachable without the edge is a route on which any caller can
 * set {@code X-Internal-User-Id} to any UUID and write into that tenant's ledger. prompt.md states
 * the filter's whole reason for existing in those terms — "so the Ledger fails closed if an
 * internal route is ever exposed without the edge in front of it, rather than trusting an
 * unauthenticated request by default" — and that sentence is what these tests assert.
 *
 * <p>Runs through the real, fully-wired servlet filter chain (@AutoConfigureMockMvc on the
 * Testcontainers-backed context) because filter ORDER and path scoping are exactly what a
 * standalone controller test cannot see.
 *
 * FAIL-TO-PASS: neither InternalCallerFilter nor the internal routes exist before this change.
 */
@AutoConfigureMockMvc
class InternalEndpointSecurityIT extends AbstractIntegrationTest {

    private static final String BULK_PATH = "/api/v1/ledger/transactions/internal/bulk";
    private static final String DUPLICATE_CHECK_PATH = "/api/v1/ledger/statements/internal/duplicate-check";
    private static final String USER_FACING_PATH = "/api/v1/ledger/statements";

    private static final String CALLER_HEADER = "X-Internal-Caller-Arn";
    /** The local/test-profile default for ledger.internal.allowed-caller-arns, per prompt.md. */
    private static final String ALLOWED_ARN = "arn:aws:iam::000000000000:role/local-dev-dispatcher";

    @Autowired
    private MockMvc mockMvc;

    private String bulkBody() {
        return """
                {"statementId":"%s","transactions":[
                  {"date":"2026-08-15","merchant":"Corner Store","amount":-42.50,
                   "category":"Groceries","subCategory":null,"type":"PURCHASE",
                   "rowFingerprint":"%s"}]}
                """.formatted(UUID.randomUUID(), "a".repeat(64));
    }

    // The core threat. A caller holding a perfectly valid end-user identity but no verified caller
    // principal — i.e. anything that reached the service without passing the edge — must not be
    // able to reach an endpoint that writes transactions.
    @Test
    @DisplayName("REQ-STMT-02: the internal bulk-create endpoint fails closed with 401 when no "
            + "X-Internal-Caller-Arn is present, even with a valid X-Internal-User-Id")
    void bulkCreateFailsClosedWithoutAVerifiedCallerPrincipal() throws Exception {
        mockMvc.perform(post(BULK_PATH)
                        .header("X-Internal-User-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkBody()))
                .andExpect(status().isUnauthorized());
    }

    // A verified principal is necessary but not sufficient: it must also be one this Ledger admits.
    // Without the allow-list check, ANY role the edge authenticates — every Lambda, task and
    // instance in the account — could write into any tenant's ledger.
    @Test
    @DisplayName("REQ-STMT-02: a verified caller ARN that is not on "
            + "ledger.internal.allowed-caller-arns is rejected with 401")
    void nonAllowListedCallerArnIsRejected() throws Exception {
        mockMvc.perform(post(BULK_PATH)
                        .header("X-Internal-User-Id", UUID.randomUUID().toString())
                        .header(CALLER_HEADER, "arn:aws:iam::000000000000:role/some-other-lambda")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkBody()))
                .andExpect(status().isUnauthorized());
    }

    // The positive direction, which keeps the three deny tests honest: without it, a filter that
    // rejected everything unconditionally would pass them all. The request is expected to fail
    // downstream (the random statementId belongs to nobody) — what matters is that it got past the
    // filter to find that out.
    @Test
    @DisplayName("REQ-STMT-02: an allow-listed caller ARN is admitted and the request reaches the "
            + "controller")
    void allowListedCallerArnIsAdmitted() throws Exception {
        mockMvc.perform(post(BULK_PATH)
                        .header("X-Internal-User-Id", UUID.randomUUID().toString())
                        .header(CALLER_HEADER, ALLOWED_ARN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkBody()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("an allow-listed caller must not be turned away by InternalCallerFilter")
                        .isNotEqualTo(401));
    }

    // REQ-STMT-03's internal duplicate-check endpoint returns another account's upload date and
    // transaction count, so it sits behind the same filter on the same "/internal/" prefix and is
    // covered by the same IAM policy.
    @Test
    @DisplayName("REQ-STMT-03: the internal duplicate-check endpoint fails closed with 401 when no "
            + "X-Internal-Caller-Arn is present")
    void duplicateCheckFailsClosedWithoutAVerifiedCallerPrincipal() throws Exception {
        mockMvc.perform(get(DUPLICATE_CHECK_PATH)
                        .header("X-Internal-User-Id", UUID.randomUUID().toString())
                        .param("accountId", UUID.randomUUID().toString())
                        .param("contentHash", "a".repeat(64)))
                .andExpect(status().isUnauthorized());
    }

    // "a verified caller identity AND an X-Internal-User-Id are both required on every internal
    // call" — with neither present the route must still be closed, never falling open.
    @Test
    @DisplayName("REQ-STMT-02: an internal call carrying neither a caller ARN nor a user identity "
            + "is 401")
    void internalCallWithNoCredentialsAtAllIsUnauthorized() throws Exception {
        mockMvc.perform(post(BULK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkBody()))
                .andExpect(status().isUnauthorized());
    }

    // The other half of the path-scoping contract ("Applies only to request paths matching
    // /api/v1/ledger/**/internal/**"). A filter registered as a plain @Component with no path
    // restriction applies to every request in the application, which would 401 every existing
    // user-facing route in the service — an outage rather than a security failure, and one no
    // other test in this suite would notice.
    @Test
    @DisplayName("REQ-STMT-02: user-facing routes are unaffected — a normal request carrying only "
            + "X-Internal-User-Id is not rejected by InternalCallerFilter")
    void userFacingRoutesAreNotAffectedByTheInternalCallerFilter() throws Exception {
        mockMvc.perform(get(USER_FACING_PATH)
                        .header("X-Internal-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
    }
}
