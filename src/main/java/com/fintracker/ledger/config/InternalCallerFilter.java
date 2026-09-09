package com.fintracker.ledger.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Inbound authorization filter for internal service-to-service routes (REQ-STMT-02).
 *
 * <p>Internal routes ({@code /api/v1/ledger/&#42;&#42;/internal/&#42;&#42;}) are authenticated by AWS
 * SigV4 request signing, verified at the edge (API Gateway's {@code AWS_IAM} authorizer,
 * or an ALB signing filter/sidecar) before the request reaches this service. This filter
 * never verifies a signature itself and holds no secret — the edge has already done the
 * cryptographic work. The edge forwards the authenticated principal in the
 * {@code X-Internal-Caller-Arn} header, overwriting any client-sent value under the same
 * name; that overwrite (a deployment requirement) is the only reason the header may be
 * treated as authoritative here.
 *
 * <p>This filter's job is authorization, not authentication: the verified caller ARN
 * must be on {@code ledger.internal.allowed-caller-arns} (deployed environments list the
 * data-pipeline dispatcher role's ARN). A missing header or an unlisted ARN is rejected
 * with 401 before any controller method runs; the rejected ARN is logged and never
 * echoed back.
 *
 * <p><b>Fail-closed by construction.</b> This filter deliberately contains NO path
 * matching of its own: it treats every request it is invoked for as internal and demands
 * the caller identity. Deciding which requests are internal is the servlet container's
 * job — the filter is registered against explicit URL patterns in
 * {@link InternalCallerFilterConfig}, and container URL matching runs on the DECODED
 * request path. An in-filter {@code shouldNotFilter()} keyed on
 * {@code getRequestURI()} would match against the RAW, undecoded URI, letting a request
 * like {@code /api/v1/ledger/transactions/%69nternal/bulk} slip past the gate while the
 * container still dispatches it to the internal controller. Keeping the filter dumb and
 * putting the mapping in one place (the registration bean) removes that entire class of
 * bypass — including any future encoding variant nobody has thought of yet.
 *
 * <p>An empty allow-list is a startup failure, not a permissive default — a service that
 * boots with nothing on the list must refuse every internal call, and refusing to start
 * says so louder.
 *
 * <p>The registration runs before {@link UserContextFilter} for the internal routes: a
 * verified caller identity AND an {@code X-Internal-User-Id} are both required on every
 * internal call.
 */
public class InternalCallerFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Internal-Caller-Arn";
    private static final Logger log = LoggerFactory.getLogger(InternalCallerFilter.class);

    private final List<String> allowedCallerArns;

    public InternalCallerFilter(List<String> allowedCallerArns) {
        var arns = allowedCallerArns == null
                ? List.<String>of()
                : allowedCallerArns.stream().map(String::strip).filter(arn -> !arn.isEmpty()).toList();
        if (arns.isEmpty()) {
            throw new IllegalStateException(
                    "ledger.internal.allowed-caller-arns must name at least one caller ARN; "
                            + "refusing to start with an empty internal-caller allow-list.");
        }
        this.allowedCallerArns = arns;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String callerArn = request.getHeader(HEADER_NAME);

        if (callerArn == null || callerArn.isBlank()) {
            log.warn("Rejected internal call: missing {} header. path={}", HEADER_NAME, request.getRequestURI());
            writeProblemDetail(response,
                    "Missing internal caller identity",
                    "The '%s' header is required but was not provided.".formatted(HEADER_NAME),
                    "https://fintracker.dev/problems/missing-internal-caller");
            return;
        }

        if (!allowedCallerArns.contains(callerArn.strip())) {
            // The rejected ARN is logged for the operator; it is never echoed back to
            // the caller in the response.
            log.warn("Rejected internal call: caller ARN '{}' is not on the allow-list. path={}",
                    callerArn, request.getRequestURI());
            writeProblemDetail(response,
                    "Internal caller not authorized",
                    "The calling principal is not permitted to invoke internal ledger routes.",
                    "https://fintracker.dev/problems/internal-caller-not-allowed");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeProblemDetail(HttpServletResponse response,
                                    String title,
                                    String detail,
                                    String type) throws IOException {
        var problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setType(URI.create(type));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"%s","title":"%s","status":%d,"detail":"%s"}
                """.formatted(type, title, HttpStatus.UNAUTHORIZED.value(), detail));
    }
}
