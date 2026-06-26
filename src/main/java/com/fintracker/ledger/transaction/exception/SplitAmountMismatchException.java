package com.fintracker.ledger.transaction.exception;

import java.math.BigDecimal;

public class SplitAmountMismatchException extends RuntimeException {
    public SplitAmountMismatchException(BigDecimal parent, BigDecimal splitTotal) {
        super("Split total %s does not match parent transaction amount %s.".formatted(splitTotal, parent));
    }
}
