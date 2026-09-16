package com.fintracker.ledger.category.controller;

import com.fintracker.ledger.category.dto.CategoryUsageResponse;
import com.fintracker.ledger.category.dto.CustomCategoryRequest;
import com.fintracker.ledger.category.dto.CustomCategoryResponse;
import com.fintracker.ledger.category.dto.DeleteCategoryRequest;
import com.fintracker.ledger.category.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** REQ-TS-01. Identity comes from the {@code userId} request attribute (UserContextFilter),
 * never from the request body — same convention as every other Ledger controller. */
@RestController
@RequestMapping("/api/v1/ledger/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PostMapping
    public ResponseEntity<CustomCategoryResponse> create(
            @Valid @RequestBody CustomCategoryRequest request, @RequestAttribute("userId") UUID userId) {
        var created = categoryService.createCustomCategory(request.categoryName(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(CustomCategoryResponse.from(created));
    }

    @GetMapping
    public ResponseEntity<List<CustomCategoryResponse>> getAll(@RequestAttribute("userId") UUID userId) {
        var categories = categoryService.getAllCategoriesForUser(userId).stream()
                .map(CustomCategoryResponse::from)
                .toList();
        return ResponseEntity.ok(categories);
    }

    @PutMapping("/{categoryId}")
    public ResponseEntity<CustomCategoryResponse> update(
            @PathVariable UUID categoryId, @Valid @RequestBody CustomCategoryRequest request,
            @RequestAttribute("userId") UUID userId) {
        var updated = categoryService.updateCustomCategory(categoryId, request.categoryName(), userId);
        return ResponseEntity.ok(CustomCategoryResponse.from(updated));
    }

    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID categoryId,
            @RequestBody(required = false) DeleteCategoryRequest request,
            @RequestAttribute("userId") UUID userId) {
        var reassignToCategoryId = request != null ? request.reassignToCategoryId() : null;
        categoryService.deleteCustomCategory(categoryId, userId, reassignToCategoryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{categoryId}/usage")
    public ResponseEntity<CategoryUsageResponse> usage(
            @PathVariable UUID categoryId, @RequestAttribute("userId") UUID userId) {
        var count = categoryService.countTransactionsUsingCategory(categoryId, userId);
        return ResponseEntity.ok(new CategoryUsageResponse(count));
    }
}
