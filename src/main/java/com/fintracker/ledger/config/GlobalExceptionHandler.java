package com.fintracker.ledger.config;

import com.fintracker.ledger.bill.exception.BillNotFoundException;
import com.fintracker.ledger.budget.exception.DuplicateCategoryException;
import com.fintracker.ledger.budget.exception.DuplicateTemplateException;
import com.fintracker.ledger.budget.exception.HistoricalBudgetException;
import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import com.fintracker.ledger.budget.exception.LineItemLimitExceededException;
import com.fintracker.ledger.shared.exception.ResourceNotFoundException;
import com.fintracker.ledger.statement.exception.StatementNotFoundException;
import com.fintracker.ledger.transaction.exception.IllegalStateTransitionException;
import com.fintracker.ledger.transaction.exception.SplitAmountMismatchException;
import com.fintracker.ledger.transaction.exception.TooManyTagsException;
import com.fintracker.ledger.transaction.exception.TransactionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Global exception handler implementing RFC 9457 Problem Details for HTTP APIs.
 * Maps domain exceptions to standardized HTTP Problem Detail responses.
 * Never exposes internal stack traces or sensitive data to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI PROBLEM_BASE = URI.create("https://api.fintracker.com/problems/");

    @ExceptionHandler(TransactionNotFoundException.class)
    public ProblemDetail handleTransactionNotFound(TransactionNotFoundException ex) {
        log.warn("Transaction not found: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setType(PROBLEM_BASE.resolve("transaction-not-found"));
        detail.setTitle("Transaction Not Found");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(StatementNotFoundException.class)
    public ProblemDetail handleStatementNotFound(StatementNotFoundException ex) {
        log.warn("Statement not found: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setType(PROBLEM_BASE.resolve("statement-not-found"));
        detail.setTitle("Statement Not Found");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(BillNotFoundException.class)
    public ProblemDetail handleBillNotFound(BillNotFoundException ex) {
        log.warn("Bill not found: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setType(PROBLEM_BASE.resolve("bill-not-found"));
        detail.setTitle("Bill Not Found");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ProblemDetail handleIllegalStateTransition(IllegalStateTransitionException ex) {
        log.warn("Illegal state transition: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setType(PROBLEM_BASE.resolve("illegal-state-transition"));
        detail.setTitle("Illegal State Transition");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(SplitAmountMismatchException.class)
    public ProblemDetail handleSplitMismatch(SplitAmountMismatchException ex) {
        log.warn("Split amount mismatch: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        detail.setType(PROBLEM_BASE.resolve("split-amount-mismatch"));
        detail.setTitle("Split Amount Mismatch");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(TooManyTagsException.class)
    public ProblemDetail handleTooManyTags(TooManyTagsException ex) {
        log.warn("Too many tags: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        detail.setType(PROBLEM_BASE.resolve("too-many-tags"));
        detail.setTitle("Too Many Tags");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setType(PROBLEM_BASE.resolve("resource-not-found"));
        detail.setTitle("Resource Not Found");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(InvalidBudgetException.class)
    public ProblemDetail handleInvalidBudget(InvalidBudgetException ex) {
        log.warn("Invalid budget payload: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setType(PROBLEM_BASE.resolve("invalid-budget"));
        detail.setTitle("Invalid Budget");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(LineItemLimitExceededException.class)
    public ProblemDetail handleLineItemLimitExceeded(LineItemLimitExceededException ex) {
        log.warn("Budget line item limit exceeded: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setType(PROBLEM_BASE.resolve("line-item-limit-exceeded"));
        detail.setTitle("Line Item Limit Exceeded");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(DuplicateCategoryException.class)
    public ProblemDetail handleDuplicateCategory(DuplicateCategoryException ex) {
        log.warn("Duplicate budget line category: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setType(PROBLEM_BASE.resolve("duplicate-category"));
        detail.setTitle("Duplicate Category");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(DuplicateTemplateException.class)
    public ProblemDetail handleDuplicateTemplate(DuplicateTemplateException ex) {
        log.warn("Duplicate budget template name: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setType(PROBLEM_BASE.resolve("duplicate-template"));
        detail.setTitle("Duplicate Template Name");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(HistoricalBudgetException.class)
    public ProblemDetail handleHistoricalBudget(HistoricalBudgetException ex) {
        log.warn("Write attempted against a closed budget: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        detail.setType(PROBLEM_BASE.resolve("historical-budget"));
        detail.setTitle("Budget Is Closed");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid argument: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setType(PROBLEM_BASE.resolve("invalid-argument"));
        detail.setTitle("Invalid Argument");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    /**
     * A request parameter or path variable that cannot be converted to its declared type —
     * {@code ?year=not-a-year}, {@code ?month=13-13-13}, a malformed UUID in a path.
     *
     * <p>Without this handler such requests fall through to {@link #handleUnexpected} and are
     * reported as 500, which tells the client the server is broken when in fact their input was.
     * It also turns a routine client mistake into a page-worthy error-rate signal.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Malformed request parameter '{}'", ex.getName());
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setType(PROBLEM_BASE.resolve("invalid-parameter"));
        detail.setTitle("Invalid Parameter");
        // The rejected value is the client's own input, echoed back so they can see what was
        // rejected; the underlying conversion exception is not surfaced, as its message can
        // expose internal type names.
        detail.setDetail("Parameter '%s' is not a valid %s.".formatted(
                ex.getName(),
                ex.getRequiredType() == null ? "value" : ex.getRequiredType().getSimpleName()));
        detail.setProperty("parameter", ex.getName());
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        log.warn("Input validation failed: {} error(s)", ex.getBindingResult().getErrorCount());
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setType(PROBLEM_BASE.resolve("validation-error"));
        detail.setTitle("Validation Failed");
        detail.setDetail("One or more fields failed validation.");
        detail.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList());
        return detail;
    }

    // ── Framework-level client errors ───────────────────────────────────────────
    //
    // Spring raises these before any controller runs. Without explicit handlers they fall through
    // to handleUnexpected and are reported as 500 — a client typo, a wrong verb or a stale client
    // calling a route that no longer exists all masquerade as a server fault. In an API that is
    // monitored on 5xx rate that is both a false alarm and a misleading answer to the caller.

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("No handler for {} {}", ex.getHttpMethod(), ex.getResourcePath());
        var detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setType(PROBLEM_BASE.resolve("endpoint-not-found"));
        detail.setTitle("Endpoint Not Found");
        detail.setDetail("No endpoint is mapped to this path.");
        return detail;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method {} not supported; supported={}", ex.getMethod(), ex.getSupportedHttpMethods());
        var detail = ProblemDetail.forStatus(HttpStatus.METHOD_NOT_ALLOWED);
        detail.setType(PROBLEM_BASE.resolve("method-not-allowed"));
        detail.setTitle("Method Not Allowed");
        detail.setDetail("%s is not supported for this endpoint.".formatted(ex.getMethod()));
        if (ex.getSupportedHttpMethods() != null) {
            detail.setProperty("supportedMethods",
                    ex.getSupportedHttpMethods().stream().map(Object::toString).toList());
        }
        return detail;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex) {
        log.warn("Missing required request parameter '{}'", ex.getParameterName());
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setType(PROBLEM_BASE.resolve("missing-parameter"));
        detail.setTitle("Missing Parameter");
        detail.setDetail("Required parameter '%s' was not provided.".formatted(ex.getParameterName()));
        detail.setProperty("parameter", ex.getParameterName());
        return detail;
    }

    /**
     * Any other request-binding failure — most notably
     * {@code UnsatisfiedServletRequestParameterException}, raised when a URL is mapped only in
     * parameter-qualified variants and the request matches none of them
     * ({@code GET /budgets} with neither {@code ?month=} nor {@code ?year=}).
     *
     * <p>Declared after the specific handlers above, which win for the exception types they name.
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ProblemDetail handleRequestBinding(ServletRequestBindingException ex) {
        log.warn("Request binding failed: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setType(PROBLEM_BASE.resolve("invalid-request"));
        detail.setTitle("Invalid Request");
        detail.setDetail("The request is missing a required parameter or does not match any "
                + "supported form of this endpoint.");
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        var detail = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        detail.setType(PROBLEM_BASE.resolve("internal-error"));
        detail.setTitle("Internal Server Error");
        detail.setDetail("An unexpected error occurred. Please try again later.");
        return detail;
    }
}
