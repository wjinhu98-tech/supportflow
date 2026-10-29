package dev.supportflow.order.api.response;

import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.order.domain.model.OrderItemSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.List;

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
        Instant receivedAt,
        List<OrderItemSnapshot> items,
        Integer itemKinds
) {
    private static final int MAX_PRODUCT_PREVIEW = 3;

    /** Old cached response JSON and existing callers keep unknown product data empty. */
    public OrderResponse(long shopId, String orderNo, String status, String statusDescription,
                         BigDecimal totalAmount, String currency, Instant createdAt, Instant updatedAt,
                         Instant receivedAt) {
        this(shopId, orderNo, status, statusDescription, totalAmount, currency,
                createdAt, updatedAt, receivedAt, List.of(), 0);
    }

    public OrderResponse {
        items = items == null ? List.of() : List.copyOf(items);
        itemKinds = itemKinds == null ? items.size() : itemKinds;
        if (items.size() > MAX_PRODUCT_PREVIEW || itemKinds < items.size() || itemKinds > OrderItemSnapshot.MAX_ITEMS
                || items.size() != Math.min(itemKinds, MAX_PRODUCT_PREVIEW))
            throw new IllegalArgumentException("Invalid public product preview");
    }

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
                order.receivedAt(),
                order.items().stream().limit(MAX_PRODUCT_PREVIEW).toList(),
                order.items().size()
        );
    }
}
