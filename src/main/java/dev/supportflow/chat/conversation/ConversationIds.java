package dev.supportflow.chat.conversation;

import dev.supportflow.chat.application.InvalidChatCommandException;
import java.util.UUID;

/** UUID.fromString alone accepts shortened groups; public identifiers use the full UUID form. */
public final class ConversationIds {
    public static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private ConversationIds() { }

    public static UUID parse(String value) {
        if (value == null) return null;
        if (!value.matches(UUID_PATTERN)) throw new InvalidChatCommandException("会话或消息标识格式不正确");
        return UUID.fromString(value);
    }
}
