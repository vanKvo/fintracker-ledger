package com.fintracker.ledger.account.controller;

import com.fintracker.ledger.account.dto.AccountDto;
import com.fintracker.ledger.account.dto.CreateAccountRequest;
import com.fintracker.ledger.account.dto.UpdateAccountRequest;
import com.fintracker.ledger.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public ResponseEntity<List<AccountDto>> getAccounts(@RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(accountService.getAccountsForUser(userId));
    }

    /** REQ-3.2 "Create New Account". */
    @PostMapping
    public ResponseEntity<AccountDto> createAccount(@Valid @RequestBody CreateAccountRequest request,
                                                     @RequestAttribute("userId") UUID userId) {
        var created = accountService.createAccount(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** REQ-3.1 inline editing. */
    @PatchMapping("/{id}")
    public ResponseEntity<AccountDto> updateAccount(@PathVariable UUID id,
                                                     @RequestBody UpdateAccountRequest request,
                                                     @RequestAttribute("userId") UUID userId) {
        return ResponseEntity.ok(accountService.updateAccount(id, request, userId));
    }
}
