package dev.supportflow.chat.conversation;

import java.util.List;
import java.util.UUID;

public record ConversationMessages(Conversation conversation, List<ConversationMessage> messages, UUID nextBeforeId) {
    public ConversationMessages { messages = List.copyOf(messages); }
}
