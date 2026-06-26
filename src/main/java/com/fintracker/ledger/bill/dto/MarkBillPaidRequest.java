package com.fintracker.ledger.bill.dto;

import java.util.UUID;

public record MarkBillPaidRequest(
        UUID transactionId
) {}
