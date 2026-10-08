package com.fintracker.ledger.category.dto;

import com.fintracker.ledger.category.model.Category;

import java.util.List;
import java.util.UUID;

/**
 * DP-LEDGER-CATEGORIES-02: one user's own categories, fetched once per upload. An object rather
 * than a bare array so the user's merchant rules can be added alongside later.
 */
public record InternalUserCategoriesResponse(UUID userId, List<UserCategory> categories) {

    public record UserCategory(UUID categoryId, String categoryName, boolean isActive) {
    }

    public static InternalUserCategoriesResponse of(UUID userId, List<Category> categories) {
        return new InternalUserCategoriesResponse(userId, categories.stream()
                .map(c -> new UserCategory(c.categoryId(), CustomCategoryResponse.from(c).displayName(), c.isActive()))
                .toList());
    }
}
