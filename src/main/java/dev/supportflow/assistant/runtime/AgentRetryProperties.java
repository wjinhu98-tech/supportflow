package dev.supportflow.assistant.runtime;

import dev.supportflow.assistant.runtime.model.AgentRetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "supportflow.agent.retry", ignoreUnknownFields = false)
public record AgentRetryProperties(@DefaultValue("1") int maxReadRetries,
                                    @DefaultValue("250ms") Duration delay) {
    public AgentRetryProperties {
        if (delay == null || delay.isNegative() || delay.compareTo(Duration.ofSeconds(1)) > 0)
            throw new IllegalArgumentException("Retry delay must be between zero and one second");
        new AgentRetryPolicy(maxReadRetries, maxReadRetries == 0 ? 0 : Math.toIntExact(delay.toMillis()));
    }
    public AgentRetryPolicy policy() {
        return new AgentRetryPolicy(maxReadRetries, maxReadRetries == 0 ? 0 : Math.toIntExact(delay.toMillis()));
    }
}
