package com.fintracker.ledger.config;

import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * REQ-STMT-04's write endpoint behind the same fail-closed gate as every other internal route.
 *
 * <p>InternalEndpointSecurityIT already proves InternalCallerFilter works for the bulk-create and
 * duplicate-check routes. This class exists because the filter is path-scoped: a new route added
 * under the internal prefix is only covered if the scoping actually catches it, and nothing in the
 * filter's own tests would notice a new endpoint that fell outside the pattern.
 *
 * <p>The stakes are specific to this route: it is a WRITE reachable with a caller-supplied
 * X-Internal-User-Id. Unguarded, any caller could stamp a fingerprint onto any tenant's statement,
 * which then makes that tenant's next legitimate upload look like a duplicate.
 */
@AutoConfigureMockMvc
@DisplayName("REQ-STMT-04 fingerprint endpoint caller authentication")
class InternalFingerprintEndpointSecurityIT extends AbstractIntegrationTest {

    private static final String CALLER_HEADER = "X-Internal-Caller-Arn";
    /** The local/test-profile default for ledger.internal.allowed-caller-arns. */
    private static final String ALLOWED_ARN = "arn:aws:iam::000000000000:role/local-dev-dispatcher";

    @Autowired private MockMvc mockMvc;

    private String path() {
        return "/api/v1/ledger/statements/internal/%s/content-fingerprint".formatted(UUID.randomUUID());
    }

    private String body() {
        return "{\"contentFingerprint\":\"" + "a".repeat(64) + "\"}";
    }

    @Test
    @DisplayName("fails closed with 401 when no verified caller principal is present")
    @Tag("multi-tenant")
    void failsClosedWithoutACallerPrincipal() throws Exception {
        var status = mockMvc.perform(patch(path())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body())
                        .header("X-Internal-User-Id", UUID.randomUUID().toString()))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("an internal write route reachable without the edge in front of it is a route "
                        + "on which any caller can name any tenant")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a verified caller ARN that is not on the allow-list is rejected with 401")
    @Tag("multi-tenant")
    void rejectsACallerNotOnTheAllowList() throws Exception {
        var status = mockMvc.perform(patch(path())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body())
                        .header(CALLER_HEADER, "arn:aws:iam::999999999999:role/some-other-lambda")
                        .header("X-Internal-User-Id", UUID.randomUUID().toString()))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }

    @Test
    @DisplayName("an allow-listed caller reaches the controller — the gate admits the real caller, "
            + "it does not simply reject everything")
    void anAllowListedCallerReachesTheController() throws Exception {
        var status = mockMvc.perform(patch(path())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body())
                        .header(CALLER_HEADER, ALLOWED_ARN)
                        .header("X-Internal-User-Id", UUID.randomUUID().toString()))
                .andReturn().getResponse().getStatus();

        // 404: the random statement id does not exist for that user. Reaching a 404 proves the
        // request got past the filter to the handler — which is what this test is about. A test
        // that only ever asserted 401 would still pass with the route accidentally blocked to
        // everyone, including the pipeline.
        assertThat(status)
                .as("must not be 401 — the allow-listed caller has to get through")
                .isEqualTo(404);
    }
}
