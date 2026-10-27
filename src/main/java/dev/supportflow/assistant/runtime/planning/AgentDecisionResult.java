package dev.supportflow.assistant.runtime.planning;

import dev.supportflow.assistant.runtime.model.AgentDecision;
import dev.supportflow.assistant.runtime.model.AgentTokenUsage;
import java.util.Objects;

public record AgentDecisionResult(AgentDecision decision, AgentTokenUsage usage) {
    public AgentDecisionResult { Objects.requireNonNull(decision); Objects.requireNonNull(usage); }
}
