package com.fintracker.ledger.api.controller;

import com.fintracker.ledger.api.dto.BulkActionRequest;
import com.fintracker.ledger.api.dto.ManualTransactionRequest;
import com.fintracker.ledger.api.dto.SplitTransactionRequest;
import com.fintracker.ledger.domain.model.Transaction;
import com.fintracker.ledger.domain.ports.outbound.TransactionFilter;
import com.fintracker.ledger.domain.service.TransactionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Inbound REST Adapter: Transactions Module.
 * Translates HTTP requests into domain calls. No business logic here.
 */
@RestController
@RequestMapping("/api/v1/ledger/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    public ResponseEntity<List<Transaction>> getTransactions(
            @RequestAttribute("userId") UUID userId,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) String merchant,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) Transaction.TransactionStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size
    ) {
        var filter = new TransactionFilter(userId, accountId, merchant, dateFrom, dateTo,
                category, tags, status, page, size);
        return ResponseEntity.ok(transactionService.getTransactions(filter));
    }

    @PutMapping("/{id}/approve")
    public ResponseEntity<Void> approveTransaction(@PathVariable UUID id) {
        transactionService.approveTransaction(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/exclude")
    public ResponseEntity<Void> toggleExclude(@PathVariable UUID id,
                                               @RequestParam boolean exclude) {
        transactionService.toggleExclude(id, exclude);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/split")
    public ResponseEntity<List<Transaction>> splitTransaction(
            @PathVariable UUID id,
            @Valid @RequestBody SplitTransactionRequest request
    ) {
        var splitRequests = request.splits().stream()
                .map(s -> new TransactionService.SplitRequest(s.amount(), s.category()))
                .toList();
        return ResponseEntity.ok(transactionService.splitTransaction(id, splitRequests));
    }

    @PostMapping("/bulk")
    public ResponseEntity<Void> bulkAction(@Valid @RequestBody BulkActionRequest request) {
        switch (request.action()) {
            case APPROVE -> transactionService.bulkApprove(request.transactionIds());
            case EXCLUDE -> request.transactionIds().forEach(id -> transactionService.toggleExclude(id, true));
            case INCLUDE -> request.transactionIds().forEach(id -> transactionService.toggleExclude(id, false));
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable UUID id) {
        transactionService.deleteManualTransaction(id);
        return ResponseEntity.noContent().build();
    }
}
