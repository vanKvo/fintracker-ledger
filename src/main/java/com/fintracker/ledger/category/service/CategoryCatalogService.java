package com.fintracker.ledger.category.service;

import com.fintracker.ledger.category.exception.InternalUserMismatchException;
import com.fintracker.ledger.category.model.Category;
import com.fintracker.ledger.category.repository.CategoryReadRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** DP-LEDGER-CATEGORIES-02: the read-only category views the Data Pipeline caches. */
@Service
public class CategoryCatalogService {

    private final CategoryReadRepository categoryReadRepository;

    public CategoryCatalogService(CategoryReadRepository categoryReadRepository) {
        this.categoryReadRepository = categoryReadRepository;
    }

    public List<Category> systemCategories() {
        return categoryReadRepository.findAllSystem();
    }

    /**
     * The path user must be the caller's own (X-Internal-User-Id): an internal caller can only
     * read the user it is acting for, never pick another tenant through the URL.
     */
    public List<Category> userCategories(UUID requestedUserId, UUID callerUserId) {
        if (!requestedUserId.equals(callerUserId)) {
            throw new InternalUserMismatchException();
        }
        return categoryReadRepository.findAllUserCategories(callerUserId);
    }
}
