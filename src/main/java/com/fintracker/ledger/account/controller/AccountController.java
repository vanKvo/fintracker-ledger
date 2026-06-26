package com.fintracker.ledger.account.controller;

import com.fintracker.ledger.account.dto.AccountDto;
import com.fintracker.ledger.account.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
