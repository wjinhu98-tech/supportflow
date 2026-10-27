package dev.supportflow.chat.infrastructure.deepseek;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "supportflow.ai.deepseek.circuit", ignoreUnknownFields = false)
public record DeepSeekCircuitProperties(@DefaultValue("5") int failureThreshold,
                                        @DefaultValue("30s") Duration openDuration) {
    public DeepSeekCircuitProperties {
        if (failureThreshold < 1 || failureThreshold > 100 || openDuration == null
                || openDuration.compareTo(Duration.ofSeconds(1)) < 0
                || openDuration.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Invalid model circuit limits");
    }
    public static DeepSeekCircuitProperties defaults() {
        return new DeepSeekCircuitProperties(5, Duration.ofSeconds(30));
    }
}
