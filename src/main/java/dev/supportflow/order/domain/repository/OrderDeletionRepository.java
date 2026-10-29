package dev.supportflow.order.domain.repository;

import dev.supportflow.order.domain.model.OrderDeletionTarget;

import java.util.Optional;

public interface OrderDeletionRepository {
    Optional<OrderDeletionTarget> lockOwnedOrder(long shopId, long buyerId, String orderNo);
    boolean isDeleted(OrderDeletionTarget order);
    boolean hasPendingAfterSale(OrderDeletionTarget order);
    void appendDeletion(OrderDeletionTarget order, String actor);
}
