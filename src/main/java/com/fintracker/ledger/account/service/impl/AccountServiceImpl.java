package com.fintracker.ledger.account.service.impl;

import com.fintracker.ledger.account.dto.AccountDto;
import com.fintracker.ledger.account.dto.CreateAccountRequest;
import com.fintracker.ledger.account.dto.UpdateAccountRequest;
import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.account.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AccountServiceImpl implements AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceImpl.class);

    // REQ-3.1.D: "Account Name, Account Type, Owner: Alphanumeric letters, space, hyphen, and
    // dash are only allowable characters." Treating "hyphen" and "dash" as the same character
    // ('-') since the spec doesn't distinguish en/em dash from hyphen-minus anywhere else.
    private static final Pattern NAME_FIELD_PATTERN = Pattern.compile("^[A-Za-z0-9 -]+$");

    // REQ-3.1.D: "Account number: Alphanumeric letters only."
    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");

    private static final String DEFAULT_SYNC_MODE = "MANUAL";
    private static final Set<String> VALID_SYNC_MODES = Set.of("MANUAL", "AUTOMATED");

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

    @Override
    public AccountDto createAccount(CreateAccountRequest request, UUID userId) {
        validateNameField("accountName", request.accountName());
        validateNameField("accountType", request.accountType());
        if (request.owner() != null) {
            validateNameField("owner", request.owner());
        }
        if (request.accountNumber() != null) {
            validateAccountNumber(request.accountNumber());
        }

        // REQ-3.2.B: Sync Mode defaults to Manual when the request omits it.
        var syncMode = request.syncMode() != null ? request.syncMode() : DEFAULT_SYNC_MODE;
        validateSyncMode(syncMode);

        var created = accountRepository.insert(userId, request.accountName(), request.accountType(),
                request.accountNumber(), request.owner(), syncMode);
        log.info("Created account accountId={} userId={}", created.accountId(), userId);
        return created;
    }

    @Override
    public AccountDto updateAccount(UUID accountId, UpdateAccountRequest request, UUID userId) {
        // Ownership check first — same findByIdAndUserId-style guard every other write path in
        // this codebase enforces (see TransactionServiceImpl.approveTransaction et al.), so a
        // user can never edit another tenant's account regardless of what the UI shows them.
        if (!accountRepository.existsByIdAndUserId(accountId, userId)) {
            throw new IllegalArgumentException(
                    "Account %s does not belong to the requesting user.".formatted(accountId));
        }

        if (request.accountName() != null) {
            validateNameField("accountName", request.accountName());
        }
        if (request.accountType() != null) {
            validateNameField("accountType", request.accountType());
        }
        if (request.owner() != null) {
            validateNameField("owner", request.owner());
        }
        if (request.accountNumber() != null) {
            validateAccountNumber(request.accountNumber());
        }
        if (request.syncMode() != null) {
            validateSyncMode(request.syncMode());
        }

        var updated = accountRepository.update(accountId, request.accountName(), request.accountType(),
                request.accountNumber(), request.owner(), request.syncMode());
        log.info("Updated account accountId={} userId={}", accountId, userId);
        return updated;
    }

    private void validateNameField(String fieldName, String value) {
        if (!NAME_FIELD_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "%s may only contain letters, numbers, spaces, and hyphens.".formatted(fieldName));
        }
    }

    private void validateAccountNumber(String accountNumber) {
        if (!ACCOUNT_NUMBER_PATTERN.matcher(accountNumber).matches()) {
            throw new IllegalArgumentException("accountNumber may only contain letters and numbers.");
        }
    }

    private void validateSyncMode(String syncMode) {
        if (!VALID_SYNC_MODES.contains(syncMode)) {
            throw new IllegalArgumentException("syncMode must be one of %s.".formatted(VALID_SYNC_MODES));
        }
    }
}
