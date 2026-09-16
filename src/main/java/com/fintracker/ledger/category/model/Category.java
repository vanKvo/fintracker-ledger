package com.fintracker.ledger.category.model;

import java.util.UUID;

/**
 * REQ-TS-01. {@code categoryName} always holds the normalized form (Requested Changes #3:
 * lowercase, single-underscore-separated) — the Title Case/underscore-to-space display form
 * (Requested Changes #4) is derived at read time, never stored.
 */
public record Category(UUID categoryId, String categoryName, Level level, UUID userId) {

    public enum Level { SYSTEM, USER }
}
