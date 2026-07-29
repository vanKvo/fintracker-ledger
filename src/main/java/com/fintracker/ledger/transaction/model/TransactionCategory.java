package com.fintracker.ledger.transaction.model;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REQ-2.2 "Inline Row Modification" business constraint: "The categories must be a drop-down
 * list that is defined by the system." ledger.transactions.category remains a free VARCHAR(100)
 * column (no DB-level enum/CHECK constraint), but every write that runs through the service layer
 * is funneled through {@link #resolve(String)} so the stored value always comes from this fixed,
 * known set.
 */
public enum TransactionCategory {
    GROCERIES("Groceries"),
    DINING("Dining"),
    TRANSPORTATION("Transportation"),
    SHOPPING("Shopping"),
    ENTERTAINMENT("Entertainment"),
    UTILITIES("Utilities"),
    HOUSING("Housing"),
    HEALTHCARE("Healthcare"),
    INSURANCE("Insurance"),
    SUBSCRIPTIONS("Subscriptions"),
    TRAVEL("Travel"),
    EDUCATION("Education"),
    PERSONAL_CARE("Personal Care"),
    INCOME("Income"),
    TRANSFER("Transfer"),
    FEES("Fees"),
    OTHERS("Others");

    private final String label;

    TransactionCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    private static final Map<String, TransactionCategory> BY_LABEL = Arrays.stream(values())
            .collect(Collectors.toMap(c -> c.label.toLowerCase(), c -> c));

    public static final List<String> LABELS = Arrays.stream(values())
            .map(TransactionCategory::label)
            .toList();

    /**
     * Case-insensitive match against the canonical label. Anything unrecognized — legacy
     * free-text data, upstream ingestion quirks, typos — collapses to OTHERS rather than being
     * rejected outright, since categorization mistakes are exactly what REQ-2.2 lets users fix
     * after the fact.
     */
    public static TransactionCategory resolve(String rawCategory) {
        if (rawCategory == null) {
            return OTHERS;
        }
        return BY_LABEL.getOrDefault(rawCategory.trim().toLowerCase(), OTHERS);
    }
}
