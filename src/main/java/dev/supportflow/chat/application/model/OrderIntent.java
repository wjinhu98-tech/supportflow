package dev.supportflow.chat.application.model;

import java.util.Objects;

/** 模型提取的业务参数，缺失字段保留null，由应用服务提示补充。 */
public record OrderIntent(OrderIntentType type, Long shopId, String orderNo) {

    public OrderIntent {
        Objects.requireNonNull(type, "intent type must not be null");
        if (type == OrderIntentType.OTHER && (shopId != null || orderNo != null)) {
            throw new IllegalArgumentException("OTHER must not contain lookup parameters");
        }
        if (shopId != null && shopId <= 0) {
            throw new IllegalArgumentException("shopId must be positive");
        }
        if (orderNo != null && (orderNo.isBlank() || orderNo.length() > 64
                || orderNo.codePoints().anyMatch(codePoint ->
                        Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)))) {
            throw new IllegalArgumentException("Invalid extracted order number");
        }
    }

    public static OrderIntent other() {
        return new OrderIntent(OrderIntentType.OTHER, null, null);
    }

    public static OrderIntent lookup(Long shopId, String orderNo) {
        return new OrderIntent(OrderIntentType.ORDER_LOOKUP, shopId, orderNo);
    }
}
