package com.fintracker.ledger.category.dto;

import com.fintracker.ledger.category.model.Category;

import java.util.UUID;

/** DP-LEDGER-CATEGORIES-02: one SYSTEM category as the Data Pipeline caches it. */
public record InternalSystemCategoryResponse(UUID categoryId, String code, String categoryName, boolean isActive) {

    public static InternalSystemCategoryResponse from(Category category) {
        return new InternalSystemCategoryResponse(category.categoryId(), category.code(),
                CustomCategoryResponse.from(category).displayName(), category.isActive());
    }
}
