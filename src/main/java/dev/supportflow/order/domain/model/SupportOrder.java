package dev.supportflow.order.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 已持久化订单的只读业务快照。
 *
 * <p>用于订单查询，不承担数据库访问、HTTP 响应或订单状态流转。</p>
 */
public record SupportOrder(
        long id,
        long tenantId,
        long customerId,
        long buyerId,
        String orderNo,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        Instant createdAt,
        Instant updatedAt,
        Instant receivedAt
) {

    /** Historical fixtures and orders without a trusted receipt event keep this value unknown. */
    public SupportOrder(long id, long tenantId, long customerId, long buyerId, String orderNo,
                        OrderStatus status, BigDecimal totalAmount, String currency,
                        Instant createdAt, Instant updatedAt) {
        this(id, tenantId, customerId, buyerId, orderNo, status, totalAmount, currency,
                createdAt, updatedAt, null);
    }

    public SupportOrder {
        if (buyerId <= 0) {
            throw new IllegalArgumentException("buyerId must be positive");
        }
        Objects.requireNonNull(orderNo, "orderNo must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (totalAmount.signum() < 0) {
            throw new IllegalArgumentException(
                    "totalAmount must not be negative"
            );
        }
    }
}
