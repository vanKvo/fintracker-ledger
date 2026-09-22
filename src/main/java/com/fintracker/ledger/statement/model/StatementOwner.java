package com.fintracker.ledger.statement.model;

import java.util.UUID;

/**
 * REQ-DP-05: a statement's account/user ownership, looked up by {@code statement_id} alone —
 * see {@link com.fintracker.ledger.statement.repository.StatementRepository#findOwnerByStatementId}.
 * Kept separate from {@link Statement} rather than adding a {@code userId} field to it: every
 * other Statement read is already scoped by the caller's own userId, so the domain model never
 * needed to carry it as a field of its own until this one, deliberately different, lookup came
 * along.
 */
public record StatementOwner(UUID accountId, UUID userId) {}
