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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/**
 * Inbound Security Filter: extracts the internal user identity set by API Gateway.
 *
 * In the serverless production topology, API Gateway validates the Cognito JWT
 * and resolves the opaque Cognito sub → internal UUID via the user-profile-service.
 * It then forwards the resolved identity as the {@code X-Internal-User-Id} header.
 *
 * This filter reads that header and binds the UUID as a request attribute ("userId"),
 * making it available to every downstream controller via {@code @RequestAttribute("userId")}.
 * Requests without the header are rejected with 401 Unauthorized.
 */
@Component
public class UserContextFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Internal-User-Id";
    private static final String ATTRIBUTE    = "userId";
    private static final Logger log          = LoggerFactory.getLogger(UserContextFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String rawHeader = request.getHeader(HEADER_NAME);

        if (rawHeader == null || rawHeader.isBlank()) {
            log.warn("Rejected request: missing {} header. path={}", HEADER_NAME, request.getRequestURI());
            writeProblemDetail(response, HttpStatus.UNAUTHORIZED,
                    "Missing identity header",
                    "The '%s' header is required but was not provided.".formatted(HEADER_NAME),
                    "https://fintracker.dev/problems/missing-user-identity");
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(rawHeader.strip());
        } catch (IllegalArgumentException ex) {
            log.warn("Rejected request: malformed {} header value='{}'. path={}",
                    HEADER_NAME, rawHeader, request.getRequestURI());
            writeProblemDetail(response, HttpStatus.BAD_REQUEST,
                    "Malformed identity header",
                    "The '%s' header must be a valid UUID.".formatted(HEADER_NAME),
                    "https://fintracker.dev/problems/malformed-user-identity");
            return;
        }

        request.setAttribute(ATTRIBUTE, userId);
        log.debug("User context set. userId={} path={}", userId, request.getRequestURI());
        filterChain.doFilter(request, response);
    }

    private void writeProblemDetail(HttpServletResponse response,
                                    HttpStatus status,
                                    String title,
                                    String detail,
                                    String type) throws IOException {
        var problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setType(URI.create(type));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"%s","title":"%s","status":%d,"detail":"%s"}
                """.formatted(type, title, status.value(), detail));
    }
}
