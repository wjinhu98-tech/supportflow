package dev.supportflow.assistant.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Per-run limits are captured at creation; recovery cannot refill an existing run's budget. */
@ConfigurationProperties(prefix = "supportflow.agent.runtime", ignoreUnknownFields = false)
public record AgentRuntimeProperties(@DefaultValue("8") int maxSteps,
                                     @DefaultValue("90") int totalTimeoutSeconds) {
    public AgentRuntimeProperties {
        if (maxSteps < 1 || maxSteps > 32) {
            throw new IllegalArgumentException("Agent maxSteps must be between 1 and 32");
        }
        // Leave room inside the conversation's processing lease for the final message transaction.
        if (totalTimeoutSeconds < 1 || totalTimeoutSeconds > 100) {
            throw new IllegalArgumentException("Agent totalTimeoutSeconds must be between 1 and 100");
        }
    }
}
