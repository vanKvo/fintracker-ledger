package com.fintracker.ledger.account.service;

import com.fintracker.ledger.account.dto.AccountDto;
import com.fintracker.ledger.account.dto.CreateAccountRequest;
import com.fintracker.ledger.account.dto.UpdateAccountRequest;
import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.account.service.impl.AccountServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Coverage map to docs/fintracker-ledger-doc/ledger-spec, Module 3 "Accounts":
 *
 *   - REQ-3.1 "Table and its functions in the Accounts main page" (inline editing) — covered by
 *     the UpdateAccount nested class below: field validation, syncMode validation, and the
 *     tenant-ownership check.
 *   - REQ-3.2 "Create New Account" — covered by the CreateAccount nested class below: syncMode
 *     defaulting to MANUAL, and the same field validation.
 *
 *   Not covered here: the Angular Accounts page (fintracker-ui/src/app/features/accounts/) that
 *   consumes this API — outside what this Java test suite can verify.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService Unit Tests")
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        accountService = new AccountServiceImpl(accountRepository);
        userId = UUID.randomUUID();
    }

    private static AccountDto accountDto(UUID userId, String accountName, String accountType,
                                          String accountNumber, String owner, String syncMode) {
        return new AccountDto(UUID.randomUUID(), userId, accountName, accountType,
                accountNumber, owner, BigDecimal.ZERO, syncMode, OffsetDateTime.now());
    }

    @Nested
    @DisplayName("createAccount()")
    class CreateAccount {

        @Test
        @DisplayName("REQ-3.2.B: should default syncMode to MANUAL when the request omits it")
        void shouldDefaultSyncModeToManualWhenNotProvided() {
            var request = new CreateAccountRequest(
                    "Chase Checking", "CHECKING", null, "Jane Doe", null);
            when(accountRepository.insert(eq(userId), eq("Chase Checking"), eq("CHECKING"),
                    eq((String) null), eq("Jane Doe"), eq("MANUAL")))
                    .thenReturn(accountDto(userId, "Chase Checking", "CHECKING", null, "Jane Doe", "MANUAL"));

            var result = accountService.createAccount(request, userId);

            assertThat(result.syncMode()).isEqualTo("MANUAL");
        }

        @Test
        @DisplayName("REQ-3.1.D: should reject an accountName containing characters other than "
                + "alphanumeric, space, hyphen, or dash")
        void shouldRejectInvalidCharactersInAccountName() {
            var request = new CreateAccountRequest(
                    "Chase Checking!!", "CHECKING", null, "Jane Doe", "MANUAL");

            assertThatThrownBy(() -> accountService.createAccount(request, userId))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("REQ-3.1.D: should reject an owner containing characters other than "
                + "alphanumeric, space, hyphen, or dash")
        void shouldRejectInvalidCharactersInOwner() {
            var request = new CreateAccountRequest(
                    "Chase Checking", "CHECKING", null, "Jane Doe!!", "MANUAL");

            assertThatThrownBy(() -> accountService.createAccount(request, userId))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("REQ-3.1.D: should reject an accountNumber containing non-alphanumeric characters")
        void shouldRejectInvalidCharactersInAccountNumber() {
            var request = new CreateAccountRequest(
                    "Chase Checking", "CHECKING", "1234-5678", "Jane Doe", "MANUAL");

            assertThatThrownBy(() -> accountService.createAccount(request, userId))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Business constraint (REQ-3.1.D): should reject a syncMode other than "
                + "MANUAL or AUTOMATED")
        void shouldRejectInvalidSyncMode() {
            var request = new CreateAccountRequest(
                    "Chase Checking", "CHECKING", null, "Jane Doe", "HYBRID");

            assertThatThrownBy(() -> accountService.createAccount(request, userId))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("REQ-3.2.C: should persist the new account for the requesting user")
        void shouldCreateAccountForRequestingUser() {
            var request = new CreateAccountRequest(
                    "Chase Checking", "CHECKING", null, "Jane Doe", "MANUAL");
            when(accountRepository.insert(eq(userId), eq("Chase Checking"), eq("CHECKING"),
                    eq((String) null), eq("Jane Doe"), eq("MANUAL")))
                    .thenReturn(accountDto(userId, "Chase Checking", "CHECKING", null, "Jane Doe", "MANUAL"));

            var result = accountService.createAccount(request, userId);

            assertThat(result.userId()).isEqualTo(userId);
            assertThat(result.accountName()).isEqualTo("Chase Checking");
        }

        @Test
        @DisplayName("REQ-3.1.D: the full accountNumber the user typed is passed through to the "
                + "repository and returned as-is — masking to the last 4 digits is a UI-only "
                + "display concern, not something the service or repository does")
        void shouldPassFullAccountNumberThroughUnmasked() {
            var request = new CreateAccountRequest(
                    "Chase Checking", "CHECKING", "123456789", "Jane Doe", "MANUAL");
            when(accountRepository.insert(eq(userId), any(), any(), eq("123456789"), any(), any()))
                    .thenReturn(accountDto(userId, "Chase Checking", "CHECKING", "123456789", "Jane Doe", "MANUAL"));

            var result = accountService.createAccount(request, userId);

            assertThat(result.accountNumber()).isEqualTo("123456789");
        }
    }

    @Nested
    @DisplayName("updateAccount()")
    class UpdateAccount {

        @Test
        @DisplayName("REQ-3.1: should update the account name in place")
        void shouldUpdateAccountName() {
            var accountId = UUID.randomUUID();
            var request = new UpdateAccountRequest("New Name", null, null, null, null);
            when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(true);
            when(accountRepository.update(accountId, "New Name", null, null, null, null))
                    .thenReturn(accountDto(userId, "New Name", "CHECKING", null, null, "MANUAL"));

            var result = accountService.updateAccount(accountId, request, userId);

            assertThat(result.accountName()).isEqualTo("New Name");
        }

        @Test
        @DisplayName("REQ-3.1.D: should reject an accountType containing disallowed characters")
        void shouldRejectInvalidCharactersInAccountType() {
            var accountId = UUID.randomUUID();
            var request = new UpdateAccountRequest(null, "CHECKING/SAVINGS", null, null, null);
            when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(true);

            assertThatThrownBy(() -> accountService.updateAccount(accountId, request, userId))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Business constraint (REQ-3.1.D): should reject switching syncMode to a "
                + "value other than MANUAL or AUTOMATED")
        void shouldRejectInvalidSyncMode() {
            var accountId = UUID.randomUUID();
            var request = new UpdateAccountRequest(null, null, null, null, "HYBRID");
            when(accountRepository.existsByIdAndUserId(accountId, userId)).thenReturn(true);

            assertThatThrownBy(() -> accountService.updateAccount(accountId, request, userId))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Inferred tenant-isolation constraint (REQ-1.1's spirit): should reject "
                + "updating an account that doesn't belong to the requesting user — same "
                + "findByIdAndUserId-style ownership check every other write path in this codebase "
                + "already enforces (see TransactionServiceImpl.approveTransaction et al.)")
        void shouldRejectWhenAccountDoesNotBelongToUser() {
            var someoneElsesAccountId = UUID.randomUUID();
            var request = new UpdateAccountRequest("New Name", null, null, null, null);
            when(accountRepository.existsByIdAndUserId(someoneElsesAccountId, userId)).thenReturn(false);

            assertThatThrownBy(() ->
                    accountService.updateAccount(someoneElsesAccountId, request, userId))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
