package com.fintracker.ledger.domain.ports.outbound;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.fintracker.ledger.domain.model.Account;

/** Outbound port for account balance aggregation. */
public interface AccountRepository {

    BigDecimal sumTotalBalanceByUserId(UUID userId);

    List<Account> findAccountsByUserId(UUID userId);
}
