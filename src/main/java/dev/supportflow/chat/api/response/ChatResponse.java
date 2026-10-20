package dev.supportflow.chat.api.response;

import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.chat.application.model.ChatReplyType;
import dev.supportflow.order.api.response.OrderResponse;

import java.util.List;

/** 订单卡片只使用公开订单DTO，不序列化内部主键、买家ID或历史客户ID。 */
public record ChatResponse(
        String reply,
        ChatReplyType type,
        List<OrderResponse> orders,
        Long nextAfterId
) {
    public ChatResponse {
        orders = List.copyOf(orders);
    }

    public static ChatResponse from(ChatReply result) {
        return new ChatResponse(
                result.reply(), result.type(),
                result.orders().stream().map(OrderResponse::from).toList(),
                result.nextAfterId());
    }
}
