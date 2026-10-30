package dev.supportflow.chat.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.supportflow.chat.conversation.ConversationIds;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public views deliberately omit internal identities, AI prompts and business operation controls. */
public final class HumanSupportModels {
    private HumanSupportModels() { }
    public enum Status { WAITING, ACTIVE, CLOSED }
    public enum Role { BUYER, STAFF }
    public record StartRequest(@NotNull UUID requestId) {
        @JsonCreator
        public static StartRequest fromJson(@JsonProperty("requestId") String id) {
            return new StartRequest(ConversationIds.parse(id));
        }
    }
    public record MessageRequest(@NotNull UUID clientMessageId,
                                 @NotBlank @Size(max = 2000) String text) {
        @JsonCreator
        public static MessageRequest fromJson(@JsonProperty("clientMessageId") String id, @JsonProperty("text") String text) {
            return new MessageRequest(ConversationIds.parse(id), text);
        }
    }
    public record SessionView(UUID id, Status status, boolean assigned, boolean assignedToMe,
                              Instant createdAt, Instant updatedAt, Instant closedAt) { }
    public record MessageView(UUID id, UUID clientMessageId, Role role, String text, Instant createdAt) { }
    public record Snapshot(SessionView session, List<MessageView> messages, UUID nextBeforeId) { }
    public record MessagePage(List<MessageView> messages, UUID nextBeforeId) { }
    public record SessionPage(List<SessionView> items, UUID nextBeforeId) { }
}
