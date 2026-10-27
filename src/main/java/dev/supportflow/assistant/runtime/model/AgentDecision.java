package dev.supportflow.assistant.runtime.model;

import dev.supportflow.assistant.planning.PlannedToolCall;
import dev.supportflow.assistant.planning.ToolName;
import java.util.Objects;

public record AgentDecision(AgentAction action, String orderNo, String callId) {
    public AgentDecision {
        Objects.requireNonNull(action);
        new PlannedToolCall(ToolName.ORDER_LOOKUP, orderNo);
        if (callId == null || !callId.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new IllegalArgumentException("Invalid model call identifier");
    }
}
