package com.fintracker.ledger.shared.exception;

import java.util.UUID;

/**
 * A resource does not exist <em>or does not belong to the requesting user</em> — the two cases are
 * deliberately indistinguishable to the caller so that a 404 cannot be used to probe for the
 * existence of another tenant's data. Maps to 404 Not Found.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceType, UUID id) {
        super("%s %s was not found.".formatted(resourceType, id));
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
