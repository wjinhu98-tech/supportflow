package dev.supportflow.chat.conversation;

import dev.supportflow.order.domain.OrderQueryLimits;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Server-generated, bounded subject pointers. Facts and permissions must be re-read on every use. */
public record ConversationContextRef(ConversationContextKind kind, String orderNo,
                                     List<String> sourceIds, Instant expiresAt) {
    public ConversationContextRef {
        Objects.requireNonNull(kind, "context kind must not be null");
        sourceIds = List.copyOf(Objects.requireNonNull(sourceIds, "source ids must not be null"));
        if (sourceIds.size() > 2 || new HashSet<>(sourceIds).size() != sourceIds.size()
                || sourceIds.stream().anyMatch(id -> !id.matches("[a-z][a-z0-9-]{1,63}"))) {
            throw new IllegalArgumentException("Invalid context source references");
        }
        boolean valid = switch (kind) {
            case NONE -> orderNo == null && sourceIds.isEmpty() && expiresAt == null;
            case ORDER -> orderNo != null && !orderNo.isBlank()
                    && orderNo.length() <= OrderQueryLimits.MAX_ORDER_NO_LENGTH
                    && orderNo.codePoints().noneMatch(point -> Character.isWhitespace(point)
                    || Character.isSpaceChar(point) || Character.isISOControl(point))
                    && sourceIds.isEmpty() && expiresAt != null;
            case KNOWLEDGE -> orderNo == null && !sourceIds.isEmpty() && expiresAt != null;
        };
        if (!valid) throw new IllegalArgumentException("Context kind and references disagree");
    }

    public static ConversationContextRef none() {
        return new ConversationContextRef(ConversationContextKind.NONE, null, List.of(), null);
    }

    public static ConversationContextRef order(String orderNo, Instant expiresAt) {
        return new ConversationContextRef(ConversationContextKind.ORDER, orderNo, List.of(), expiresAt);
    }

    public static ConversationContextRef knowledge(List<String> sourceIds, Instant expiresAt) {
        return new ConversationContextRef(ConversationContextKind.KNOWLEDGE, null, sourceIds, expiresAt);
    }

    public boolean activeAt(Instant now) {
        return kind != ConversationContextKind.NONE && expiresAt.isAfter(now)
                && !expiresAt.isAfter(now.plusSeconds(ConversationLimits.CONTEXT_TTL_SECONDS));
    }
}
