package dev.supportflow.order.application.exception;

import java.io.Serial;

/** Fixed customer-facing reasons; no SQL or untrusted order data is included. */
public final class OrderDeletionConflictException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private OrderDeletionConflictException(String message) { super(message); }

    public static OrderDeletionConflictException unfinishedOrder() {
        return new OrderDeletionConflictException("仅已完成或已取消的订单可以删除。");
    }

    public static OrderDeletionConflictException pendingAfterSale() {
        return new OrderDeletionConflictException("该订单仍有待处理售后，请处理完成后再删除。");
    }
}
