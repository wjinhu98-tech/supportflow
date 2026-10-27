package dev.supportflow.assistant.runtime.planning;

import dev.supportflow.assistant.runtime.model.AgentModelProfile;

public interface AgentDecisionPlanner {
    AgentModelProfile profile();
    AgentDecisionResult decide(AgentDecisionInput input);
}
