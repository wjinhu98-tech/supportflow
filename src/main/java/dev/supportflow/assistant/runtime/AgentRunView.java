package dev.supportflow.assistant.runtime;

import dev.supportflow.assistant.runtime.store.AgentPhase;
import dev.supportflow.assistant.runtime.store.AgentRunStatus;
import dev.supportflow.assistant.runtime.store.AgentStepView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public progress only. No buyer IDs, raw requests, provider responses, or stored planning inputs. */
public record AgentRunView(UUID runId, UUID clientMessageId, AgentRunStatus status, AgentPhase phase,
                           int attempts, int maxSteps, Instant deadlineAt, boolean resumable,
                           String result, List<AgentStepView> steps) {
    public AgentRunView {
        steps = List.copyOf(steps);
    }
}
