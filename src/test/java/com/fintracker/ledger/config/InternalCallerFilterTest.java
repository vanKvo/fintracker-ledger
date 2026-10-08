package com.fintracker.ledger.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * REQ-STMT-02: the internal-caller allow-list gate in front of the internal routes. The
 * SigV4 signature itself is verified at the edge (API Gateway / ALB sidecar) — this
 * filter only authorizes the already-verified caller ARN the edge forwarded, so it is
 * unit-testable without any crypto: the header stands in for the edge's assertion.
 *
 * <p>The filter itself is deliberately path-blind (fail-closed): every request it is
 * invoked for is treated as internal. The decision of WHICH requests are internal lives
 * solely in {@link InternalCallerFilterConfig}'s URL-pattern registration, matched by
 * the container against the decoded path — there is no raw-URI check to bypass.
 */
@DisplayName("InternalCallerFilter Unit Tests")
class InternalCallerFilterTest {

    private static final String ALLOWED_ARN = "arn:aws:iam::000000000000:role/local-dev-dispatcher";
    private static final String INTERNAL_PATH = "/api/v1/ledger/transactions/internal/bulk";
    private static final String USER_PATH = "/api/v1/ledger/transactions";

    private InternalCallerFilter filter;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new InternalCallerFilter(List.of(ALLOWED_ARN));
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    private MockHttpServletRequest request(String path, String callerArn) {
        var request = new MockHttpServletRequest("POST", path);
        if (callerArn != null) {
            request.addHeader("X-Internal-Caller-Arn", callerArn);
        }
        return request;
    }

    @Test
    @DisplayName("fail-closed: even a non-internal path is gated if the filter is invoked — "
            + "there is no URI-based skip an encoded request could bypass")
    void filterNeverSkipsBasedOnPath() throws ServletException, IOException {
        // Before the FilterRegistrationBean redesign this request would have been passed
        // through by shouldNotFilter(); the container's URL-pattern registration is now
        // what keeps it away from the filter in production. If the filter is ever
        // invoked for it anyway, it must demand the caller identity, not pass.
        filter.doFilter(request(USER_PATH, null), response, chain);

        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("an internal call with no X-Internal-Caller-Arn is rejected 401 before the chain runs")
    void internalPathWithoutHeaderIsRejected() throws ServletException, IOException {
        filter.doFilter(request(INTERNAL_PATH, null), response, chain);

        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    @Test
    @DisplayName("a caller ARN not on the allow-list is rejected 401, and the ARN is never echoed back")
    void unlistedCallerArnIsRejected() throws ServletException, IOException {
        var attackerArn = "arn:aws:iam::999999999999:role/some-other-role";

        filter.doFilter(request(INTERNAL_PATH, attackerArn), response, chain);

        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentAsString()).doesNotContain(attackerArn);
    }

    @Test
    @DisplayName("an allow-listed caller ARN proceeds down the chain")
    void allowListedCallerArnProceeds() throws ServletException, IOException {
        filter.doFilter(request(INTERNAL_PATH, ALLOWED_ARN), response, chain);

        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("an empty allow-list is a startup failure, not a permissive default")
    void emptyAllowListFailsFast() {
        assertThatThrownBy(() -> new InternalCallerFilter(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger.internal.allowed-caller-arns");
    }

    @Test
    @DisplayName("an allow-list of only blank entries is equally a startup failure")
    void blankOnlyAllowListFailsFast() {
        assertThatThrownBy(() -> new InternalCallerFilter(List.of(" ", "")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Nested
    @DisplayName("FilterRegistrationBean registration")
    class Registration {

        private org.springframework.boot.web.servlet.FilterRegistrationBean<InternalCallerFilter> registration() {
            return new InternalCallerFilterConfig()
                    .internalCallerFilterRegistration(List.of(ALLOWED_ARN));
        }

        @Test
        @DisplayName("the filter is registered against the explicit internal-route URL "
                + "prefixes, matched by the container on the decoded path")
        void registeredAgainstInternalUrlPrefixesOnly() {
            assertThat(registration().getUrlPatterns()).containsExactlyInAnyOrder(
                    "/api/v1/ledger/transactions/internal/*",
                    "/api/v1/ledger/statements/internal/*",
                    "/api/v1/ledger/categories/internal/*");
        }

        @Test
        @DisplayName("the registration runs before UserContextFilter — both caller identity "
                + "and X-Internal-User-Id are required on every internal call")
        void registeredAtHighestPrecedence() {
            assertThat(registration().getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        }

        @Test
        @DisplayName("registration with an empty allow-list fails startup")
        void emptyAllowListFailsRegistration() {
            assertThatThrownBy(() -> new InternalCallerFilterConfig()
                    .internalCallerFilterRegistration(List.of()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
