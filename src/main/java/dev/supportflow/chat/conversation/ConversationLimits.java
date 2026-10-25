package dev.supportflow.chat.conversation;

/** Limits are shared by request validation, storage and pagination. */
public final class ConversationLimits {
    public static final int CONVERSATIONS_PER_PAGE = 20;
    public static final int MESSAGES_PER_PAGE = 40;
    public static final int MAX_MESSAGE_LENGTH = 1000;
    public static final int MAX_REPLY_LENGTH = 20000;
    public static final int MAX_SNAPSHOT_BYTES = 262144;
    public static final int PROCESSING_SECONDS = 120;
    public static final int CONTEXT_TTL_SECONDS = 30 * 60;

    private ConversationLimits() { }
}
