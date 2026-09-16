package com.fintracker.ledger.category.repository;

import com.fintracker.ledger.category.model.Category;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository {

    /** SYSTEM-level, or USER-level owned by {@code userId} — the set Requested Changes #1 says
     * is shown to that user. */
    Optional<Category> findByIdAndAccessibleToUser(UUID categoryId, UUID userId);

    List<Category> findAllAccessibleToUser(UUID userId);

    boolean existsByNormalizedNameAccessibleToUser(String normalizedName, UUID userId);

    long countByUserId(UUID userId);

    Category insert(UUID categoryId, String normalizedName, UUID userId);

    Category update(UUID categoryId, String normalizedName);

    void delete(UUID categoryId);
}
