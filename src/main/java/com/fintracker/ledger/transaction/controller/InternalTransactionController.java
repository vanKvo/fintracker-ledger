package com.fintracker.ledger.transaction.controller;

import com.fintracker.ledger.transaction.dto.BulkCreateTransactionsRequest;
import com.fintracker.ledger.transaction.dto.BulkCreateTransactionsResponse;
import com.fintracker.ledger.transaction.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REQ-STMT-02: internal-only endpoint for the data-pipeline's dispatcher, kept separate
 * from the user-facing {@link TransactionController} so the two authentication models
 * (Cognito-derived user identity vs. AWS SigV4 caller identity) are never mixed on the
 * same route.
 *
 * <p>Callers reach this controller only after passing {@code InternalCallerFilter}
 * (verified caller ARN on the allow-list) and {@code UserContextFilter} (a valid
 * {@code X-Internal-User-Id}, bound here as the {@code userId} request attribute) — a
 * verified caller identity AND an internal user id are both required on every internal
 * call.
 */
@RestController
@RequestMapping("/api/v1/ledger/transactions/internal")
public class InternalTransactionController {

    private final TransactionService transactionService;

    public InternalTransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/bulk")
    public ResponseEntity<BulkCreateTransactionsResponse> bulkCreate(
            @Valid @RequestBody BulkCreateTransactionsRequest request,
            @RequestAttribute("userId") UUID userId) {
        var response = transactionService.bulkCreateFromStatement(
                request.statementId(), userId, request.transactions());
        return ResponseEntity.ok(response);
    }
}
