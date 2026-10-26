package dev.supportflow.assistant.runtime.store;

import dev.supportflow.assistant.planning.PlannedToolCall;
import dev.supportflow.assistant.planning.ToolName;
import dev.supportflow.chat.conversation.ConversationMessageRequest;

import java.util.Objects;

/** Immutable original request plus the bounded, server-resolved planning input. */
public record AgentRunInput(AgentRunKey key, ConversationMessageRequest request,
                            String message, String selectedOrderNo) {
    public AgentRunInput {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(message, "message must not be null");
        if (!key.clientMessageId().equals(request.clientMessageId())) {
            throw new IllegalArgumentException("Run and original request must have the same message identifier");
        }
        if (message.isBlank() || message.length() > 1000 || request.message() == null
                || request.message().isBlank() || request.message().length() > 1000) {
            throw new IllegalArgumentException("Run messages must contain 1 to 1000 characters");
        }
        new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, selectedOrderNo);
        new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, request.orderNo());
    }
}
