package dev.supportflow.assistant.runtime.store;

import java.util.List;
import dev.supportflow.assistant.runtime.model.AgentBudget;
import dev.supportflow.assistant.runtime.model.AgentLoopState;
import dev.supportflow.assistant.runtime.model.AgentTokenUsage;
import dev.supportflow.assistant.runtime.model.AgentRetryPolicy;

/** Every write is a short independent transaction. Model/tool calls must occur outside this store. */
public interface AgentRunStore {
    AgentRun create(AgentRunInput input, int maxSteps, int timeoutSeconds);
    AgentRun createLoop(AgentRunInput input, int maxSteps, int timeoutSeconds, AgentBudget budget);
    AgentRun createLoop(AgentRunInput input, int maxSteps, int timeoutSeconds, AgentBudget budget, AgentRetryPolicy retryPolicy);
    AgentRun claim(AgentRunKey key, boolean resume);
    AgentRun beginStep(AgentRunKey key, long generation, AgentPhase expected);
    AgentRun beginDecision(AgentRunKey key, long generation);
    boolean retryStep(AgentRunKey key, long generation, AgentPhase phase, AgentStepFailure failure);
    AgentRun checkpoint(AgentRunKey key, long generation, AgentPhase completed, AgentPhase next,
                        String orderNo, String result);
    AgentRun checkpointLoop(AgentRunKey key, long generation, AgentPhase completed, AgentPhase next,
                            String orderNo, String result, AgentLoopState state, AgentTokenUsage usage);
    void pause(AgentRunKey key, long generation, AgentFailureCode failureCode);
    AgentRun find(AgentRunKey key);
    List<AgentStepView> steps(AgentRunKey key);
}
