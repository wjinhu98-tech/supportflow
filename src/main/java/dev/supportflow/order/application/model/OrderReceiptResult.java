package dev.supportflow.order.application.model;

import dev.supportflow.order.domain.model.SupportOrder;

import java.time.Instant;
import java.util.Objects;

/** Internal command result; checkedAt comes from the same database that owns the receipt. */
public record OrderReceiptResult(SupportOrder order, boolean alreadyConfirmed, Instant checkedAt) {
    public OrderReceiptResult {
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(checkedAt, "checkedAt must not be null");
    }
}
