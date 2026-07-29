package com.fintracker.ledger.config;

import com.fintracker.ledger.bill.exception.BillNotFoundException;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
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
