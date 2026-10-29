package dev.supportflow.order.domain.model;

/** The locked ownership and status needed to hide an order; never exposed as an API response. */
public record OrderDeletionTarget(long id, long shopId, long buyerId, String orderNo, OrderStatus status) { }
