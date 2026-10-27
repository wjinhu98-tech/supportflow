package dev.supportflow.chat.conversation;

import java.util.List;
import java.util.UUID;

/** One visible window per buyer/shop; legacy conversations remain intact behind its combined history. */
public record ShopConversationWindow(long shopId, Conversation conversation, List<ConversationMessage> messages,
                                      UUID nextBeforeId, ConversationContextRef context) {
    public ShopConversationWindow { messages = List.copyOf(messages); }
}
