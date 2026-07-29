package com.fintracker.ledger.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * REQ-2.2 "Tag Array Appending". The character class (alphanumeric, hyphen, underscore only)
 * excludes whitespace entirely, so a tag with leading/trailing/internal spaces is rejected here
 * rather than silently trimmed — trimming is a transformation, and this layer only rejects bad
 * input; normalization (case-folding, dedup) is the service layer's job.
 */
public record AppendTagsRequest(
        @NotEmpty
        List<@NotBlank @Size(max = 50) @Pattern(regexp = "^[a-zA-Z0-9_-]+$") String> tags
) {}
