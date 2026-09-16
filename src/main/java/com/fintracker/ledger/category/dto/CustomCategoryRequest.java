package com.fintracker.ledger.category.dto;

import jakarta.validation.constraints.NotBlank;

/** REQ-TS-01. {@code categoryName} is the raw user input; normalization happens server-side. */
public record CustomCategoryRequest(@NotBlank String categoryName) {}
