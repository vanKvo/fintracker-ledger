package com.fintracker.ledger.account.repository;

import com.fintracker.ledger.account.dto.AccountDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface AccountRepository {

    BigDecimal sumTotalBalanceByUserId(UUID userId);

    List<AccountDto> findAccountsByUserId(UUID userId);
}
