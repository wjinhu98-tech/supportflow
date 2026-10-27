package dev.supportflow.assistant.runtime.store;

import dev.supportflow.chat.conversation.ConversationMessageRequest;
import dev.supportflow.assistant.runtime.model.AgentBudget;
import dev.supportflow.assistant.runtime.model.AgentLoopState;
import dev.supportflow.assistant.runtime.model.AgentRetryPolicy;
import java.time.Instant;
import java.util.UUID;

/** Internal checkpoint. Public progress DTOs must not expose request/result snapshots or owner IDs. */
public record AgentRun(UUID id, AgentRunKey key, ConversationMessageRequest request,
                       String message, String selectedOrderNo, AgentPhase phase, AgentRunStatus status,
                       String orderNo, String result, int attempts, int maxSteps, Instant deadlineAt,
                       long generation, Instant leaseUntil, AgentFailureCode failureCode,
                       int workflowVersion, AgentLoopState loopState, AgentBudget budget, AgentRetryPolicy retryPolicy) {
    public AgentRun(UUID id, AgentRunKey key, ConversationMessageRequest request, String message, String selectedOrderNo,
                    AgentPhase phase, AgentRunStatus status, String orderNo, String result, int attempts, int maxSteps,
                    Instant deadlineAt, long generation, Instant leaseUntil, AgentFailureCode failureCode) {
        this(id, key, request, message, selectedOrderNo, phase, status, orderNo, result, attempts, maxSteps,
                deadlineAt, generation, leaseUntil, failureCode, 1, null, null, AgentRetryPolicy.disabled());
    }
}
