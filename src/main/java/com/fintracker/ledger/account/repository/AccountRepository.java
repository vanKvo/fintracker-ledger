package com.fintracker.ledger.account.repository;

import com.fintracker.ledger.account.dto.AccountDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface AccountRepository {

    BigDecimal sumTotalBalanceByUserId(UUID userId);

    List<AccountDto> findAccountsByUserId(UUID userId);

    /**
     * REQ-1.1-style ownership check, used by REQ-2.3.1 "Manual Row Insertion" to reject an
     * accountId that doesn't belong to the requesting user before writing a transaction against
     * it — the UI dropdown only ever offers the user's own accounts, but the backend must not
     * rely on that alone (a direct API call could submit any accountId).
     */
    boolean existsByIdAndUserId(UUID accountId, UUID userId);

    /** REQ-3.2 "Create New Account". */
    AccountDto insert(UUID userId, String accountName, String accountType,
                       String accountNumber, String owner, String syncMode);

    /**
     * REQ-3.1 inline editing. Any parameter left {@code null} leaves that column unchanged —
     * callers (AccountServiceImpl) are responsible for the ownership check before calling this.
     */
    AccountDto update(UUID accountId, String accountName, String accountType,
                       String accountNumber, String owner, String syncMode);
}
