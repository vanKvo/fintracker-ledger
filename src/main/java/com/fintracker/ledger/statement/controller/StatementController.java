package com.fintracker.ledger.statement.controller;

import com.fintracker.ledger.statement.dto.InitiateStatementUploadRequest;
import com.fintracker.ledger.statement.dto.StatementUploadResponse;
import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.service.StatementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger/statements")
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    @GetMapping
    public ResponseEntity<List<Statement>> getStatements(@RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(statementService.getStatements(userId));
    }

    /**
     * Starts an async statement import: creates the statement record and
     * returns a presigned S3 URL. The client uploads directly to S3 next —
     * this endpoint never receives the file itself.
     *
     * <p>REQ-STMT-03: 202 Accepted — the upload is accepted for asynchronous
     * processing, not completed. A recognized duplicate is a 409 Conflict instead
     * (see GlobalExceptionHandler.handleDuplicateStatement).
     */
    @PostMapping("/initiate-upload")
    public ResponseEntity<StatementUploadResponse> initiateUpload(
            @Valid @RequestBody InitiateStatementUploadRequest request,
            @RequestAttribute("userId") UUID userId) {
        var response = statementService.initiateUpload(request, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStatement(@PathVariable UUID id,
                                                @RequestAttribute("userId") UUID userId) {
        statementService.deleteStatement(id, userId);
        return ResponseEntity.noContent().build();
    }
}
