package com.fintracker.ledger.category.repository;

import com.fintracker.ledger.category.model.Category;

import java.util.List;
import java.util.UUID;

/**
 * DP-LEDGER-CATEGORIES-02: read-only views for the Data Pipeline's category cache. Unlike
 * {@link CategoryRepository#findAllAccessibleToUser}, these include deactivated categories, each
 * carrying its {@code isActive} flag, so the pipeline can tell "inactive" from "missing".
 */
public interface CategoryReadRepository {

    List<Category> findAllSystem();

    /** The user's own USER-level categories (not SYSTEM), active and inactive. */
    List<Category> findAllUserCategories(UUID userId);
}
