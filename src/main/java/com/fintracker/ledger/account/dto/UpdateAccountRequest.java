package com.fintracker.ledger.account.dto;

/**
 * REQ-3.1 "Table and its functions in the Accounts main page" — inline editing. All fields
 * optional so a single PATCH can update any subset; a real implementation should reject a request
 * with every field null, mirroring UpdateTransactionRequest's equivalent check in
 * TransactionController.
 */
public record UpdateAccountRequest(
        String accountName,
        String accountType,
        String accountNumber,
        String owner,
        String syncMode
) {}
