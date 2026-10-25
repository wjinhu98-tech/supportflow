package dev.supportflow.chat.conversation;

/** A reference to the current subject, never a saved business decision or authorization. */
public enum ConversationContextKind {
    NONE,
    ORDER,
    KNOWLEDGE
}
