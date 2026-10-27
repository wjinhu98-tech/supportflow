package dev.supportflow.assistant.runtime.model;

/** Frozen per run. An automatic retry is permitted at most once per logical read-only step. */
public record AgentRetryPolicy(int maxRetries, int delayMillis) {
    public AgentRetryPolicy {
        if (maxRetries < 0 || maxRetries > 1 || delayMillis < 0 || delayMillis > 1000
                || maxRetries == 0 && delayMillis != 0)
            throw new IllegalArgumentException("Invalid read-only retry policy");
    }
    public static AgentRetryPolicy disabled() { return new AgentRetryPolicy(0, 0); }
}
