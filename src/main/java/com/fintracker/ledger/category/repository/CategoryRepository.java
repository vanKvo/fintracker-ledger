package com.fintracker.ledger.category.repository;

import com.fintracker.ledger.category.model.Category;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository {

    /** SYSTEM-level, or USER-level owned by {@code userId} — the set Requested Changes #1 says
     * is shown to that user. Includes deactivated categories, which existing transactions may
     * still reference. */
    Optional<Category> findByIdAndAccessibleToUser(UUID categoryId, UUID userId);

    /** Active categories only (DP-LEDGER-CATEGORIES-02). */
    List<Category> findAllAccessibleToUser(UUID userId);

    /** Active categories only, so a deactivated name can be re-created (as a reactivation). */
    boolean existsByNormalizedNameAccessibleToUser(String normalizedName, UUID userId);

    /** The user's active custom categories, i.e. what counts toward the cap. */
    long countByUserId(UUID userId);

    Optional<Category> findInactiveUserCategoryByName(String normalizedName, UUID userId);

    Category insert(UUID categoryId, String normalizedName, UUID userId);

    Category update(UUID categoryId, String normalizedName);

    /** DP-LEDGER-CATEGORIES-02: categories are deactivated, never deleted, so category_id on
     * existing transactions stays valid. */
    void deactivate(UUID categoryId);

    Category reactivate(UUID categoryId);
}
