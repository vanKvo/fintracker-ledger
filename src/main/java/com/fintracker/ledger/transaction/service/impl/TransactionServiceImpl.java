package com.fintracker.ledger.transaction.service.impl;

import com.fintracker.ledger.account.repository.AccountRepository;
import com.fintracker.ledger.statement.model.Statement;
import com.fintracker.ledger.statement.service.StatementService;
import com.fintracker.ledger.transaction.dto.ManualTransactionRequest;
import com.fintracker.ledger.transaction.model.Transaction;
import com.fintracker.ledger.transaction.model.TransactionCategory;
import com.fintracker.ledger.transaction.model.TransactionFilter;
import com.fintracker.ledger.transaction.repository.TransactionRepository;
import com.fintracker.ledger.transaction.service.TransactionService;
import com.fintracker.ledger.transaction.exception.TransactionNotFoundException;
import com.fintracker.ledger.transaction.exception.IllegalStateTransitionException;
import com.fintracker.ledger.transaction.exception.SplitAmountMismatchException;
import com.fintracker.ledger.transaction.exception.TooManyTagsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionServiceImpl implements TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionServiceImpl.class);
    private static final int MAX_TAGS_PER_TRANSACTION = 10;

    private final TransactionRepository transactionRepository;
    private final StatementService statementService;
    private final AccountRepository accountRepository;

    public TransactionServiceImpl(TransactionRepository transactionRepository,
                                  StatementService statementService,
                                  AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.statementService = statementService;
        this.accountRepository = accountRepository;
    }

    @Override
    public List<Transaction> getTransactions(TransactionFilter filter) {
        return transactionRepository.findAll(filter);
    }

    @Override
    public Transaction createManualTransaction(ManualTransactionRequest request, UUID userId) {
        // The Angular "Add Transaction" dropdown only ever lists the user's own accounts, but a
        // direct API call could submit any accountId — the backend must not rely on the UI alone
        // to enforce this (REQ-1.1's multi-tenant isolation premise).
        if (!accountRepository.existsByIdAndUserId(request.accountId(), userId)) {
            throw new IllegalArgumentException(
                    "Account %s does not belong to the requesting user.".formatted(request.accountId()));
        }

        if (request.amount().compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Transaction amount must not be zero.");
        }

        Transaction.TransactionType type;
        try {
            type = Transaction.TransactionType.valueOf(request.type());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "type must be one of %s.".formatted(
                            Arrays.toString(Transaction.TransactionType.values())));
        }

        // REQ-2.3.1.D: date defaults to today when the request omits it.
        var txDate = request.txDate() != null ? request.txDate() : LocalDate.now();

        // REQ-2.3.1.C/D: source, isManual, and status are fixed for a manually-inserted row —
        // never taken from the request.
        var transaction = new Transaction(
                null, request.accountId(), null, null, null,
                request.amount(), request.merchant(), request.category(), null,
                request.tags(), txDate,
                Transaction.TransactionSource.MANUAL_ENTRY, type,
                Transaction.TransactionStatus.POSTED,
                false, true, null);

        var saved = transactionRepository.save(transaction);
        log.info("Created manual transaction transactionId={} userId={}", saved.transactionId(), userId);
        return saved;
    }

    @Override
    public void approveTransaction(UUID transactionId, UUID userId) {
        var transaction = transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (transaction.status() != Transaction.TransactionStatus.PENDING) {
            throw new IllegalStateTransitionException(
                    "Transaction %s is not in PENDING state.".formatted(transactionId));
        }

        transactionRepository.updateStatus(transactionId, Transaction.TransactionStatus.POSTED);
        log.info("Approved transaction transactionId={}", transactionId);

        if (transaction.statementId() != null) {
            checkAndCompleteStatement(transaction.statementId());
        }
    }

    private void checkAndCompleteStatement(UUID statementId) {
        int pendingCount = transactionRepository.countPendingByStatementId(statementId);
        if (pendingCount == 0) {
            statementService.updateStatus(statementId, Statement.StatementStatus.COMPLETED);
            log.info("All transactions approved. Marked statement COMPLETED. statementId={}", statementId);
        }
    }

    @Override
    public List<Transaction> splitTransaction(UUID parentId, List<SplitRequest> splits, UUID userId) {
        var parent = transactionRepository.findByIdAndUserId(parentId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(parentId));

        if (parent.status() == Transaction.TransactionStatus.POSTED) {
            throw new IllegalStateTransitionException(
                    "Posted transactions cannot be split. transactionId=" + parentId);
        }

        BigDecimal totalSplit = splits.stream()
                .map(SplitRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (parent.amount().abs().compareTo(totalSplit.abs()) != 0) {
            throw new SplitAmountMismatchException(parent.amount(), totalSplit);
        }

        List<Transaction> children = splits.stream()
                .map(split -> buildChildTransaction(parent, split))
                .toList();

        return transactionRepository.saveAll(children);
    }

    @Override
    public void bulkApprove(List<UUID> transactionIds, UUID userId) {
        transactionIds.forEach(id -> approveTransaction(id, userId));
    }

    @Override
    public void toggleExclude(UUID transactionId, boolean exclude, UUID userId) {
        transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        transactionRepository.toggleExcluded(transactionId, exclude);
    }

    @Override
    public void updateCategory(UUID transactionId, String category, UUID userId) {
        transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        transactionRepository.updateCategory(transactionId, TransactionCategory.resolve(category).label());
    }

    @Override
    public void updateAmount(UUID transactionId, BigDecimal amount, UUID userId) {
        transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Transaction amount must not be zero.");
        }

        transactionRepository.updateAmount(transactionId, amount);
    }

    @Override
    public void appendTags(UUID transactionId, List<String> newTags, UUID userId) {
        // REQ-2.2 "Tag Array Appending". Tags are user-scoped free text (no global registry).
        // Character/length/blank validation happens at the DTO layer (AppendTagsRequest); this
        // layer owns normalization (lowercase, for case-insensitive dedup/search) and the
        // transformation logic (dedup against existing tags, both within this request and against
        // what the transaction already has, plus the max-tag-count business rule).
        var transaction = transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        var existingNormalized = transaction.tags().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        var toAppend = newTags.stream()
                .map(String::toLowerCase)
                .filter(tag -> !existingNormalized.contains(tag))
                .distinct()
                .toList();

        if (existingNormalized.size() + toAppend.size() > MAX_TAGS_PER_TRANSACTION) {
            throw new TooManyTagsException(MAX_TAGS_PER_TRANSACTION);
        }

        if (!toAppend.isEmpty()) {
            transactionRepository.appendTags(transactionId, toAppend);
        }
    }

    @Override
    public void deleteManualTransaction(UUID transactionId, UUID userId) {
        var transaction = transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (!transaction.isManual()) {
            throw new IllegalStateTransitionException(
                    "Only manual transactions can be hard-deleted. transactionId=" + transactionId);
        }
        transactionRepository.deleteManualTransaction(transactionId);
    }

    @Override
    public BigDecimal sumMonthlyIncome(UUID userId, LocalDate start, LocalDate end) {
        return transactionRepository.sumMonthlyIncome(userId, start, end);
    }

    @Override
    public BigDecimal sumMonthlyExpenses(UUID userId, LocalDate start, LocalDate end) {
        return transactionRepository.sumMonthlyExpenses(userId, start, end);
    }

    @Override
    public BigDecimal sumMonthlyExpensesPerCategory(UUID userId, LocalDate start, LocalDate end, String category) {
        return transactionRepository.sumMonthlyExpensesPerCategory(userId, start, end, category);
    }

    private Transaction buildChildTransaction(Transaction parent, SplitRequest split) {
        return new Transaction(
                null, parent.accountId(), parent.statementId(), parent.transactionId(),
                null, split.amount(), parent.merchant(), TransactionCategory.resolve(split.category()).label(),
                parent.description(), new ArrayList<>(), parent.txDate(), parent.source(), parent.type(),
                parent.status(), parent.isExcluded(), parent.isManual(), null
        );
    }
}
