package com.fintracker.ledger.category.dto;

import java.util.UUID;

/** REQ-TS-01 Requested Changes #6. Optional body — null when the category being deleted has
 * no referencing transactions, in which case no reassignment is needed. */
public record DeleteCategoryRequest(UUID reassignToCategoryId) {}
