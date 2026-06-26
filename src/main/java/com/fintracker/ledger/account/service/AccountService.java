package com.fintracker.ledger.account.service;

import com.fintracker.ledger.account.dto.AccountDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface AccountService {

    List<AccountDto> getAccountsForUser(UUID userId);

    BigDecimal sumTotalBalanceByUserId(UUID userId);
}
