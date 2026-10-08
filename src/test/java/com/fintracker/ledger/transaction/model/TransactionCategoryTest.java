package com.fintracker.ledger.transaction.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DP-LEDGER-CATEGORIES-01: the enum's labels follow the renamed SYSTEM categories until the enum is
 * retired (plan T6), and the old labels still resolve so existing callers don't fall to the default.
 */
class TransactionCategoryTest {

    @Test
    @DisplayName("labels use 'Food & Drink' and 'Uncategorized' instead of 'Dining' and 'Others'")
    void labelsFollowTheRenamedCategories() {
        assertThat(TransactionCategory.LABELS)
                .contains("Food & Drink", "Uncategorized")
                .doesNotContain("Dining", "Others");
    }

    @Test
    @DisplayName("the old 'Dining' and 'Others' labels resolve to their renamed categories")
    void oldLabelsResolveToRenamedCategories() {
        assertThat(TransactionCategory.resolve("Dining").label()).isEqualTo("Food & Drink");
        assertThat(TransactionCategory.resolve("others").label()).isEqualTo("Uncategorized");
    }

    @Test
    @DisplayName("an unknown label falls back to Uncategorized")
    void unknownLabelFallsBackToUncategorized() {
        assertThat(TransactionCategory.resolve("Pet Supplies").label()).isEqualTo("Uncategorized");
    }
}
