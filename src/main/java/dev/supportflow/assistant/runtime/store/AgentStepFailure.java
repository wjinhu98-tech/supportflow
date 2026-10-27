package dev.supportflow.assistant.runtime.store;

/** Persisted allowlist. Business rejection and malformed model responses are never automatically retried. */
public enum AgentStepFailure {
    MODEL_TIMEOUT(true), MODEL_TRANSPORT(true), MODEL_TEMPORARY(true), TOOL_TRANSIENT(true),
    MODEL_UNAVAILABLE(false), MODEL_BUSY(false), MODEL_RATE_LIMITED(false), MODEL_REJECTED(false),
    MODEL_INVALID_RESPONSE(false), MODEL_CIRCUIT_OPEN(false), MODEL_CANCELLED(false),
    EXECUTION_INTERRUPTED(false), TOOL_UNAVAILABLE(false), VALIDATION_REJECTED(false);

    private final boolean retryable;
    AgentStepFailure(boolean retryable) { this.retryable = retryable; }
    public boolean retryable() { return retryable; }
}
