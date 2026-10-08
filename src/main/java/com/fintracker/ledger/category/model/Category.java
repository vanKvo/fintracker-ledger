package com.fintracker.ledger.category.model;

import java.util.UUID;

/**
 * REQ-TS-01. {@code categoryName} always holds the normalized form (Requested Changes #3:
 * lowercase, single-underscore-separated) — the Title Case/underscore-to-space display form
 * (Requested Changes #4) is derived at read time, never stored.
 *
 * <p>DP-LEDGER-CATEGORIES-01/02: {@code code} is the immutable, environment-stable identifier of a
 * SYSTEM category (null for USER); {@code isActive} is false once a category is deactivated.
 */
public record Category(UUID categoryId, String categoryName, Level level, UUID userId, String code, boolean isActive) {

    /** A USER category, or a category whose code/active flag the caller doesn't need. */
    public Category(UUID categoryId, String categoryName, Level level, UUID userId) {
        this(categoryId, categoryName, level, userId, null, true);
    }

    public enum Level { SYSTEM, USER }
}
