package com.fintracker.ledger.statement.controller;

import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.service.StatementService;
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStatement(@PathVariable UUID id,
                                                @RequestAttribute("userId") UUID userId) {
        statementService.deleteStatement(id, userId);
        return ResponseEntity.noContent().build();
    }
}
