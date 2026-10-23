package dev.supportflow.order.api.response;

import dev.supportflow.order.domain.model.SupportOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 买家可见的订单响应；商铺ID用于区分跨商铺同号订单。
 * 不公开内部主键、平台买家ID及店铺内客户编号。
 */
public record OrderResponse(
        long shopId,
        String orderNo,
        String status,
        String statusDescription,
        BigDecimal totalAmount,
        String currency,
        Instant createdAt,
        Instant updatedAt,
        Instant receivedAt
) {

    /**
     * 将领域订单快照转换为对外响应。
     */
    public static OrderResponse from(SupportOrder order) {
        Objects.requireNonNull(order, "order must not be null");

        return new OrderResponse(
                order.tenantId(),
                order.orderNo(),
                order.status().getCode(),
                order.status().getDescription(),
                order.totalAmount(),
                order.currency(),
                order.createdAt(),
                order.updatedAt(),
                order.receivedAt()
        );
    }
}
