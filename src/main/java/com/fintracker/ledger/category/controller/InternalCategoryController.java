package com.fintracker.ledger.category.controller;

import com.fintracker.ledger.category.dto.InternalSystemCategoryResponse;
import com.fintracker.ledger.category.dto.InternalUserCategoriesResponse;
import com.fintracker.ledger.category.service.CategoryCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * DP-LEDGER-CATEGORIES-02: internal, read-only category endpoints for the Data Pipeline.
 * Guarded by InternalCallerFilter (allow-listed caller ARN); /system responses carry an ETag and
 * answer 304 on a matching If-None-Match (see InternalCategoryEtagFilterConfig).
 */
@RestController
@RequestMapping("/api/v1/ledger/categories/internal")
public class InternalCategoryController {

    private final CategoryCatalogService categoryCatalogService;

    public InternalCategoryController(CategoryCatalogService categoryCatalogService) {
        this.categoryCatalogService = categoryCatalogService;
    }

    @GetMapping("/system")
    public ResponseEntity<List<InternalSystemCategoryResponse>> systemCategories() {
        return ResponseEntity.ok(categoryCatalogService.systemCategories().stream()
                .map(InternalSystemCategoryResponse::from)
                .toList());
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<InternalUserCategoriesResponse> userCategories(
            @PathVariable UUID userId, @RequestAttribute("userId") UUID callerUserId) {
        return ResponseEntity.ok(InternalUserCategoriesResponse.of(
                callerUserId, categoryCatalogService.userCategories(userId, callerUserId)));
    }
}
