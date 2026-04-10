package com.fintracker.ledger.domain.service;

import com.fintracker.ledger.domain.model.Account;
import com.fintracker.ledger.domain.ports.outbound.AccountRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public List<Account> getAccountsForUser(UUID userId) {
        return accountRepository.findAccountsByUserId(userId);
    }
}
