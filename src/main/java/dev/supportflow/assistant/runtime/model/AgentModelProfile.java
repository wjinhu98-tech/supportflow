package dev.supportflow.assistant.runtime.model;

public record AgentModelProfile(String model, boolean metered) {
    public AgentModelProfile {
        if (model == null || !model.matches("[A-Za-z0-9_.:-]{1,100}"))
            throw new IllegalArgumentException("Invalid model profile");
    }
}
