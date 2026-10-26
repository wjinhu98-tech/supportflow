package dev.supportflow.assistant.runtime.store;

import java.util.List;

/** Every write is a short independent transaction. Model/tool calls must occur outside this store. */
public interface AgentRunStore {
    AgentRun create(AgentRunInput input, int maxSteps, int timeoutSeconds);
    AgentRun claim(AgentRunKey key, boolean resume);
    AgentRun beginStep(AgentRunKey key, long generation, AgentPhase expected);
    AgentRun checkpoint(AgentRunKey key, long generation, AgentPhase completed, AgentPhase next,
                        String orderNo, String result);
    void pause(AgentRunKey key, long generation, AgentFailureCode failureCode);
    AgentRun find(AgentRunKey key);
    List<AgentStepView> steps(AgentRunKey key);
}
