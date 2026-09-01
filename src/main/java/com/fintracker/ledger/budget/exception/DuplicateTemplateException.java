package com.fintracker.ledger.budget.exception;

/**
 * REQ-5.3 B "Template Name Uniqueness": custom template names are unique per user
 * (case-insensitive) and system template names are globally unique. Maps to 409 CONFLICT.
 */
public class DuplicateTemplateException extends RuntimeException {

    public DuplicateTemplateException(String message) {
        super(message);
    }

    public static DuplicateTemplateException forName(String name) {
        return new DuplicateTemplateException(
                "A budget template named '%s' already exists.".formatted(name));
    }
}
