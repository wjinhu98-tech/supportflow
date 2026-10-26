package dev.supportflow.assistant.runtime.store;

import java.util.Objects;
import java.util.UUID;

/** Server-owned scope. UUIDs identify an execution; they never authorize it. */
public record AgentRunKey(UUID conversationId, UUID clientMessageId, long buyerId, long shopId) {
    public AgentRunKey {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(clientMessageId, "clientMessageId must not be null");
        if (buyerId <= 0 || shopId <= 0) throw new IllegalArgumentException("Owner identifiers must be positive");
    }
}
