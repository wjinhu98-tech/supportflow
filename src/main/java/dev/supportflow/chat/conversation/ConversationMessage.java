package dev.supportflow.chat.conversation;

import dev.supportflow.chat.api.response.ChatResponse;
import java.time.Instant;
import java.util.UUID;

public record ConversationMessage(UUID id, ConversationRole role, String text,
                                  Instant createdAt, ChatResponse response) { }
