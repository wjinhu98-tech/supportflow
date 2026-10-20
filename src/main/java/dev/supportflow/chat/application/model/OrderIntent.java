package dev.supportflow.chat.application.model;

import dev.supportflow.order.domain.OrderQueryLimits;

import java.util.Objects;

/** 模型提取的业务参数，缺失字段保留null，由应用服务提示补充。 */
public record OrderIntent(OrderIntentType type, String orderNo) {

    public OrderIntent {
        Objects.requireNonNull(type, "intent type must not be null");
        if (type == OrderIntentType.OTHER && orderNo != null) {
            throw new IllegalArgumentException("OTHER must not contain lookup parameters");
        }
        if (orderNo != null && (orderNo.isBlank() || orderNo.length() > OrderQueryLimits.MAX_ORDER_NO_LENGTH
                || orderNo.codePoints().anyMatch(codePoint ->
                        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                                || Character.isISOControl(codePoint)))) {
            throw new IllegalArgumentException("Invalid extracted order number");
        }
    }

    public static OrderIntent other() {
        return new OrderIntent(OrderIntentType.OTHER, null);
    }

    public static OrderIntent lookup(String orderNo) {
        return new OrderIntent(OrderIntentType.ORDER_LOOKUP, orderNo);
    }
}
