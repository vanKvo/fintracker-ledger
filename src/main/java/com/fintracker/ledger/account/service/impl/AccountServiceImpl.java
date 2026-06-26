package com.fintracker.ledger.account.service.impl;

import com.fintracker.ledger.account.dto.AccountDto;
import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.account.service.AccountService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;

    public AccountServiceImpl(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public List<AccountDto> getAccountsForUser(UUID userId) {
        return accountRepository.findAccountsByUserId(userId);
    }

    @Override
    public BigDecimal sumTotalBalanceByUserId(UUID userId) {
        return accountRepository.sumTotalBalanceByUserId(userId);
    }
}
