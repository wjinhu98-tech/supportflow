package dev.supportflow.chat.conversation;

import java.util.List;

public record ConversationTurnResponse(Conversation conversation, List<ConversationMessage> messages) {
    public ConversationTurnResponse { messages = List.copyOf(messages); }
}
