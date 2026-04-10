package com.fintracker.ledger.api.controller;

import com.fintracker.ledger.domain.model.Statement;
import com.fintracker.ledger.domain.service.StatementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Inbound REST Adapter: Statement Metadata Module.
 */
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStatement(@PathVariable UUID id) {
        statementService.deleteStatement(id);
        return ResponseEntity.noContent().build();
    }
}
