package com.fintracker.ledger.transaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REQ-STMT-02: one call per statement — the whole statement's transactions in a single
 * request body, posted by the data-pipeline's dispatcher over a SigV4-verified internal
 * route (see InternalTransactionController).
 *
 * <p>The body deliberately carries no {@code accountId} or {@code userId} field: the
 * account every row lands in is resolved server-side from the statement that the
 * caller's {@code X-Internal-User-Id} owns, so there is nothing here to trust. The
 * caller is trusted to write transactions; it is not trusted to nominate an arbitrary
 * account to write them into.
 *
 * <p>The batch is capped at 10,000 rows — a hard bound on request size so a
 * pathological (or hostile) body cannot tie up a worker parsing, validating, and
 * buffering an unbounded list. Real bank statements are orders of magnitude smaller;
 * the dispatcher's contract is one call per statement, so anything beyond the cap is
 * a client bug to fix, not a case to stream.
 */
public record BulkCreateTransactionsRequest(
        @NotNull UUID statementId,
        @NotEmpty @Size(max = 10_000) List<@Valid TransactionLine> transactions
) {
    public record TransactionLine(
            @NotNull LocalDate date,
            @NotNull String merchant,
            @NotNull BigDecimal amount,
            @NotNull String category,
            String subCategory,
            @NotNull @Pattern(regexp = "PURCHASE|CREDIT") String type,
            @NotNull @Pattern(regexp = "[a-f0-9]{64}") String rowFingerprint
    ) {}
}
