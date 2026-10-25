package dev.supportflow.chat.api.response;

import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.chat.application.model.ChatReplyType;
import dev.supportflow.chat.application.model.KnowledgeEvidence;
import dev.supportflow.chat.conversation.ConversationContextRef;
import dev.supportflow.order.api.response.OrderResponse;

import java.util.List;

/** 订单卡片只使用公开订单DTO，不序列化内部主键、买家ID或历史客户ID。 */
public record ChatResponse(
        String reply,
        ChatReplyType type,
        List<OrderResponse> orders,
        Long nextAfterId,
        KnowledgeEvidence knowledge,
        ConversationContextRef context
) {
    /** Compatibility for existing callers and historical response_json without knowledge metadata. */
    public ChatResponse(String reply, ChatReplyType type, List<OrderResponse> orders, Long nextAfterId) {
        this(reply, type, orders, nextAfterId, null, null);
    }

    public ChatResponse(String reply, ChatReplyType type, List<OrderResponse> orders,
                        Long nextAfterId, KnowledgeEvidence knowledge) {
        this(reply, type, orders, nextAfterId, knowledge, null);
    }

    public ChatResponse {
        orders = List.copyOf(orders);
        if (knowledge != null && (type != ChatReplyType.TEXT || !orders.isEmpty() || nextAfterId != null)) {
            throw new IllegalArgumentException("Knowledge evidence cannot replace business results");
        }
    }

    public static ChatResponse from(ChatReply result) {
        return new ChatResponse(
                result.reply(), result.type(),
                result.orders().stream().map(OrderResponse::from).toList(),
                result.nextAfterId(), result.knowledge());
    }

    public ChatResponse withContext(ConversationContextRef reference) {
        return new ChatResponse(reply, type, orders, nextAfterId, knowledge,
                java.util.Objects.requireNonNull(reference, "server context must not be null"));
    }
}
