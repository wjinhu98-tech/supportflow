package dev.supportflow.assistant.runtime.store;

/** Persist fixed codes only. Provider messages, SQL, and credentials must never enter this field. */
public enum AgentFailureCode {
    EXECUTION_INTERRUPTED, MODEL_UNAVAILABLE, TOOL_UNAVAILABLE, VALIDATION_REJECTED,
    DEADLINE_EXCEEDED, STEP_LIMIT_REACHED, MODEL_BUDGET_EXCEEDED
}
