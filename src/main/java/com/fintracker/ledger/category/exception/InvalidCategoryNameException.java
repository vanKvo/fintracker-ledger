package com.fintracker.ledger.category.exception;

/** REQ-TS-01 Requested Changes #3/#9: a category name with disallowed characters, blank after
 * normalization, or over the 100-character cap. Mapped to 400 Bad Request. */
public class InvalidCategoryNameException extends RuntimeException {

    public InvalidCategoryNameException(String message) {
        super(message);
    }
}
