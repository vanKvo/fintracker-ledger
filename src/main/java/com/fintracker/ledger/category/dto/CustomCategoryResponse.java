package com.fintracker.ledger.category.dto;

import com.fintracker.ledger.category.model.Category;

import java.util.UUID;

/**
 * REQ-TS-01 Requested Changes #4: {@code displayName} is the Title Case, underscore-to-space
 * form of the stored {@code Category.categoryName}, computed here rather than stored.
 */
public record CustomCategoryResponse(UUID categoryId, String displayName, String level) {

    public static CustomCategoryResponse from(Category category) {
        return new CustomCategoryResponse(category.categoryId(), toDisplayName(category.categoryName()),
                category.level().name());
    }

    private static String toDisplayName(String normalizedName) {
        var words = normalizedName.split("_");
        var builder = new StringBuilder();
        for (var word : words) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }
}
