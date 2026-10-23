package dev.supportflow.chat.conversation;

import java.util.List;
import java.util.UUID;

public record ConversationPage(List<Conversation> conversations, UUID nextBeforeId) {
    public ConversationPage { conversations = List.copyOf(conversations); }
}
