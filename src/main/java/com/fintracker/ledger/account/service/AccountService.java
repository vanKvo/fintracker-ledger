package com.fintracker.ledger.account.service;

import com.fintracker.ledger.account.dto.AccountDto;
import com.fintracker.ledger.account.dto.CreateAccountRequest;
import com.fintracker.ledger.account.dto.UpdateAccountRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface AccountService {

    List<AccountDto> getAccountsForUser(UUID userId);

    BigDecimal sumTotalBalanceByUserId(UUID userId);

    /**
     * REQ-3.2 "Create New Account". syncMode defaults to MANUAL when omitted; accountName/
     * accountType/owner are restricted to alphanumeric + space + hyphen, accountNumber to
     * alphanumeric only (REQ-3.1.D).
     */
    AccountDto createAccount(CreateAccountRequest request, UUID userId);

    /**
     * REQ-3.1 inline editing. Rejects an accountId that doesn't belong to userId. Same field
     * validation as createAccount.
     */
    AccountDto updateAccount(UUID accountId, UpdateAccountRequest request, UUID userId);
}
