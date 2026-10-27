package dev.supportflow.assistant.runtime.model;

public record AgentTokenUsage(int promptTokens, int completionTokens) {
    public static final AgentTokenUsage NONE = new AgentTokenUsage(0, 0);
    public AgentTokenUsage {
        if (promptTokens < 0 || completionTokens < 0 || promptTokens > 1000000 || completionTokens > 1000000)
            throw new IllegalArgumentException("Invalid token usage");
    }
    public long total() { return (long) promptTokens + completionTokens; }
}
