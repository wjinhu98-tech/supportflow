package dev.supportflow.order.application.exception;

import java.io.Serial;

/** The current order cannot transition to a newly confirmed receipt. */
public final class OrderReceiptConflictException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public OrderReceiptConflictException() {
        super("Order receipt state conflicts with confirmation");
    }
}
