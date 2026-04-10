package com.fintracker.ledger.api;

import com.fintracker.ledger.domain.service.BudgetService;
import com.fintracker.ledger.domain.service.DashboardService;
import com.fintracker.ledger.domain.service.StatementService;
import com.fintracker.ledger.domain.service.TransactionService;
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

    @ExceptionHandler(TransactionService.TransactionNotFoundException.class)
    public ProblemDetail handleTransactionNotFound(TransactionService.TransactionNotFoundException ex) {
        log.warn("Transaction not found: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setType(PROBLEM_BASE.resolve("transaction-not-found"));
        detail.setTitle("Transaction Not Found");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(StatementService.StatementNotFoundException.class)
    public ProblemDetail handleStatementNotFound(StatementService.StatementNotFoundException ex) {
        log.warn("Statement not found: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setType(PROBLEM_BASE.resolve("statement-not-found"));
        detail.setTitle("Statement Not Found");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(DashboardService.BillNotFoundException.class)
    public ProblemDetail handleBillNotFound(DashboardService.BillNotFoundException ex) {
        log.warn("Bill not found: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setType(PROBLEM_BASE.resolve("bill-not-found"));
        detail.setTitle("Bill Not Found");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(TransactionService.IllegalStateTransitionException.class)
    public ProblemDetail handleIllegalStateTransition(TransactionService.IllegalStateTransitionException ex) {
        log.warn("Illegal state transition: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setType(PROBLEM_BASE.resolve("illegal-state-transition"));
        detail.setTitle("Illegal State Transition");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(TransactionService.SplitAmountMismatchException.class)
    public ProblemDetail handleSplitMismatch(TransactionService.SplitAmountMismatchException ex) {
        log.warn("Split amount mismatch: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        detail.setType(PROBLEM_BASE.resolve("split-amount-mismatch"));
        detail.setTitle("Split Amount Mismatch");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(BudgetService.PastMonthModificationException.class)
    public ProblemDetail handlePastMonthModification(BudgetService.PastMonthModificationException ex) {
        log.warn("Past month modification attempt: {}", ex.getMessage());
        var detail = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        detail.setType(PROBLEM_BASE.resolve("past-month-read-only"));
        detail.setTitle("Past Month Is Read-Only");
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
