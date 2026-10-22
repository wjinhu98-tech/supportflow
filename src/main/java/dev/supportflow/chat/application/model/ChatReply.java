package dev.supportflow.chat.application.model;

import dev.supportflow.order.domain.model.SupportOrder;

import java.util.List;
import java.util.Objects;

/** 聊天应用层结果；Controller负责把订单领域对象映射成公开DTO。 */
public record ChatReply(
        String reply,
        ChatReplyType type,
        List<SupportOrder> orders,
        Long nextAfterId
) {
    public ChatReply {
        Objects.requireNonNull(reply, "reply must not be null");
        Objects.requireNonNull(type, "type must not be null");
        orders = List.copyOf(orders);
        if (nextAfterId != null && nextAfterId <= 0) {
            throw new IllegalArgumentException("nextAfterId must be positive");
        }
        if (type == ChatReplyType.TEXT && (!orders.isEmpty() || nextAfterId != null)
                || type == ChatReplyType.ORDER_DETAILS && (orders.size() != 1 || nextAfterId != null)
                || type == ChatReplyType.AFTER_SALE_FORM && (orders.size() > 1 || nextAfterId != null)
                || orders.isEmpty() && nextAfterId != null) {
            throw new IllegalArgumentException("Invalid chat result");
        }
    }

    public static ChatReply text(String reply) {
        return new ChatReply(reply, ChatReplyType.TEXT, List.of(), null);
    }

    public static ChatReply details(String reply, SupportOrder order) {
        return new ChatReply(reply, ChatReplyType.ORDER_DETAILS, List.of(order), null);
    }

    public static ChatReply selection(String reply, List<SupportOrder> orders, Long nextAfterId) {
        return new ChatReply(reply, ChatReplyType.ORDER_SELECTION, orders, nextAfterId);
    }

    public static ChatReply refundForm(String reply, SupportOrder order) {
        return new ChatReply(reply, ChatReplyType.AFTER_SALE_FORM, order == null ? List.of() : List.of(order), null);
    }
}
