package com.fintracker.ledger.category.service;

import com.fintracker.ledger.category.model.Category;

import java.util.List;
import java.util.UUID;

public interface CategoryService {

    Category createCustomCategory(String categoryName, UUID userId);

    List<Category> getAllCategoriesForUser(UUID userId);

    Category updateCustomCategory(UUID categoryId, String newName, UUID userId);

    long countTransactionsUsingCategory(UUID categoryId, UUID userId);

    /** {@code reassignToCategoryId} is null when the category being deleted has no
     * referencing transactions. */
    void deleteCustomCategory(UUID categoryId, UUID userId, UUID reassignToCategoryId);
}
