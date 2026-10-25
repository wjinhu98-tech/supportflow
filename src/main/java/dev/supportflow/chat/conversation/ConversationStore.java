package dev.supportflow.chat.conversation;

import dev.supportflow.chat.api.response.ChatResponse;
import java.util.UUID;

/** Each mutation owns a short transaction; remote/model execution must remain outside it. */
public interface ConversationStore {
    Conversation create(long buyerId, long shopId);
    ConversationPage list(long buyerId, long shopId, UUID beforeId);
    ConversationMessages messages(long buyerId, long shopId, UUID conversationId, UUID beforeId);
    Reservation reserve(long buyerId, long shopId, UUID conversationId, ConversationMessageRequest request);
    ConversationTurnResponse succeed(long buyerId, long shopId, UUID conversationId,
                                     ConversationMessageRequest request, ChatResponse response);
    void fail(long buyerId, long shopId, UUID conversationId, UUID clientMessageId);

    /** A successful cached turn is a read, never a second orchestration. */
    record Reservation(ConversationTurnResponse cachedResponse, ConversationContextRef context) {
        public Reservation(ConversationTurnResponse cachedResponse) {
            this(cachedResponse, ConversationContextRef.none());
        }
        public Reservation {
            context = context == null ? ConversationContextRef.none() : context;
        }
        public boolean cached() { return cachedResponse != null; }
        public static Reservation fresh() { return new Reservation(null); }
        public static Reservation fresh(ConversationContextRef context) { return new Reservation(null, context); }
    }
}
