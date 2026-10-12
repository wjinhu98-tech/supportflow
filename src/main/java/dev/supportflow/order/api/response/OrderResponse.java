package dev.supportflow.order.api.response;

import dev.supportflow.order.domain.model.SupportOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 当前客户可见的订单响应，不包含内部主键及归属标识。
 */
public record OrderResponse(
        String orderNo,
        String status,
        String statusDescription,
        BigDecimal totalAmount,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 将领域订单快照转换为对外响应。
     */
    public static OrderResponse from(SupportOrder order) {
        Objects.requireNonNull(order, "order must not be null");

        return new OrderResponse(
                order.orderNo(),
                order.status().getCode(),
                order.status().getDescription(),
                order.totalAmount(),
                order.currency(),
                order.createdAt(),
                order.updatedAt()
        );
    }
}
