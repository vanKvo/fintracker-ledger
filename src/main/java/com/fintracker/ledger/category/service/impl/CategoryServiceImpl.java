package com.fintracker.ledger.category.service.impl;

import com.fintracker.ledger.category.exception.CategoryAlreadyExistsException;
import com.fintracker.ledger.category.exception.CategoryInUseException;
import com.fintracker.ledger.category.exception.CategoryLimitExceededException;
import com.fintracker.ledger.category.exception.CategoryNotFoundException;
import com.fintracker.ledger.category.exception.InvalidCategoryNameException;
import com.fintracker.ledger.category.exception.SystemCategoryImmutableException;
import com.fintracker.ledger.category.model.Category;
import com.fintracker.ledger.category.repository.CategoryRepository;
import com.fintracker.ledger.category.service.CategoryService;
import com.fintracker.ledger.transaction.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * REQ-TS-01. See ledger-transaction-spec-01.md's Requested Changes for the numbered rules
 * referenced throughout (e.g. "#3" = Normalization of user-level category).
 */
@Service
public class CategoryServiceImpl implements CategoryService {

    private static final int MAX_NAME_LENGTH = 100;
    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("^[A-Za-z0-9 ]*$");

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;

    public CategoryServiceImpl(CategoryRepository categoryRepository, TransactionRepository transactionRepository) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    public Category createCustomCategory(String categoryName, UUID userId) {
        var normalized = validateAndNormalize(categoryName);

        if (categoryRepository.existsByNormalizedNameAccessibleToUser(normalized, userId)) {
            throw new CategoryAlreadyExistsException(normalized);
        }
        if (categoryRepository.countByUserId(userId) >= CategoryLimitExceededException.MAX_CUSTOM_CATEGORIES) {
            throw new CategoryLimitExceededException();
        }

        return categoryRepository.insert(UUID.randomUUID(), normalized, userId);
    }

    @Override
    public List<Category> getAllCategoriesForUser(UUID userId) {
        // #4: sorting the stored (lowercase, underscore-separated) form ascending produces the
        // same order as sorting the Title Case/space display form — both share the same letter
        // sequence, differing only in case and separator, neither of which affects which letter
        // comes first.
        return categoryRepository.findAllAccessibleToUser(userId).stream()
                .sorted(Comparator.comparing(Category::categoryName))
                .toList();
    }

    @Override
    public Category updateCustomCategory(UUID categoryId, String newName, UUID userId) {
        var existing = requireAccessible(categoryId, userId);
        requireNotSystem(existing);

        var normalized = validateAndNormalize(newName);

        if (!normalized.equals(existing.categoryName())
                && categoryRepository.existsByNormalizedNameAccessibleToUser(normalized, userId)) {
            throw new CategoryAlreadyExistsException(normalized);
        }

        return categoryRepository.update(categoryId, normalized);
    }

    @Override
    public long countTransactionsUsingCategory(UUID categoryId, UUID userId) {
        requireAccessible(categoryId, userId);
        return transactionRepository.countByCategoryIdAndUserId(categoryId, userId);
    }

    @Override
    public void deleteCustomCategory(UUID categoryId, UUID userId, UUID reassignToCategoryId) {
        var existing = requireAccessible(categoryId, userId);
        requireNotSystem(existing);

        var referencingCount = transactionRepository.countByCategoryIdAndUserId(categoryId, userId);
        if (referencingCount > 0) {
            if (reassignToCategoryId == null) {
                throw new CategoryInUseException(categoryId, referencingCount);
            }
            if (reassignToCategoryId.equals(categoryId)) {
                throw new IllegalArgumentException(
                        "reassignToCategoryId must not be the category being deleted.");
            }
            requireAccessible(reassignToCategoryId, userId);
            transactionRepository.reassignCategory(categoryId, reassignToCategoryId, userId);
        }

        categoryRepository.delete(categoryId);
    }

    private Category requireAccessible(UUID categoryId, UUID userId) {
        return categoryRepository.findByIdAndAccessibleToUser(categoryId, userId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));
    }

    private void requireNotSystem(Category category) {
        if (category.level() == Category.Level.SYSTEM) {
            throw new SystemCategoryImmutableException(category.categoryId());
        }
    }

    /** #3: character validation, then #9's length cap, then normalization (lowercase, single
     * underscore between words), then #3/#9's blank-after-normalization check. */
    private String validateAndNormalize(String rawInput) {
        if (rawInput == null || !ALLOWED_CHARACTERS.matcher(rawInput).matches()) {
            throw new InvalidCategoryNameException(
                    "Category names may only contain letters, numbers, and spaces.");
        }
        if (rawInput.length() > MAX_NAME_LENGTH) {
            throw new InvalidCategoryNameException(
                    "Category names must be %d characters or fewer.".formatted(MAX_NAME_LENGTH));
        }

        var normalized = rawInput.trim().replaceAll("\\s+", "_").toLowerCase();

        if (normalized.isBlank()) {
            throw new InvalidCategoryNameException("Category name must not be blank.");
        }
        return normalized;
    }
}
