package dev.supportflow.assistant.runtime.store;

import dev.supportflow.chat.conversation.ConversationMessageRequest;
import java.time.Instant;
import java.util.UUID;

/** Internal checkpoint. Public progress DTOs must not expose request/result snapshots or owner IDs. */
public record AgentRun(UUID id, AgentRunKey key, ConversationMessageRequest request,
                       String message, String selectedOrderNo, AgentPhase phase, AgentRunStatus status,
                       String orderNo, String result, int attempts, int maxSteps, Instant deadlineAt,
                       long generation, Instant leaseUntil, AgentFailureCode failureCode) { }
