package dev.supportflow.chat.conversation;

import java.time.Instant;
import java.util.UUID;

/** Public projection: owner identities and storage keys never leave the repository. */
public record Conversation(UUID id, long shopId, Instant createdAt, Instant updatedAt) { }
