package com.fintracker.ledger.bill.exception;

import java.util.UUID;

public class BillNotFoundException extends RuntimeException {
    public BillNotFoundException(UUID id) {
        super("Upcoming bill not found: " + id);
    }
}
