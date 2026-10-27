package dev.supportflow.assistant.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import dev.supportflow.assistant.runtime.model.AgentBudget;
import dev.supportflow.assistant.runtime.model.AgentModelProfile;
import java.math.BigDecimal;

/** Per-run limits are captured at creation; recovery cannot refill an existing run's budget. */
@ConfigurationProperties(prefix = "supportflow.agent.runtime", ignoreUnknownFields = false)
public record AgentRuntimeProperties(@DefaultValue("8") int maxSteps,
                                     @DefaultValue("90") int totalTimeoutSeconds,
                                     @DefaultValue("60000") long maxTotalTokens,
                                     @DefaultValue("0.05") BigDecimal maxCostUsd,
                                     @DefaultValue("16384") int inputTokenAllowance,
                                     @DefaultValue("512") int outputTokenAllowance,
                                     @DefaultValue("0.30") BigDecimal inputUsdPerMillion,
                                     @DefaultValue("1.20") BigDecimal outputUsdPerMillion,
                                     @DefaultValue("deepseek-flash") String pricingModel) {
    public AgentRuntimeProperties(int maxSteps, int totalTimeoutSeconds) {
        this(maxSteps, totalTimeoutSeconds, 60000, new BigDecimal("0.05"), 16384, 512,
                new BigDecimal("0.30"), new BigDecimal("1.20"), "deepseek-flash");
    }
    @ConstructorBinding
    public AgentRuntimeProperties {
        if (maxSteps < 1 || maxSteps > 32) {
            throw new IllegalArgumentException("Agent maxSteps must be between 1 and 32");
        }
        // Leave room inside the conversation's processing lease for the final message transaction.
        if (totalTimeoutSeconds < 1 || totalTimeoutSeconds > 100) {
            throw new IllegalArgumentException("Agent totalTimeoutSeconds must be between 1 and 100");
        }
        new AgentBudget(new AgentModelProfile(pricingModel, true), maxTotalTokens, 0, 0,
                maxCostUsd, BigDecimal.ZERO, inputTokenAllowance, outputTokenAllowance, inputUsdPerMillion, outputUsdPerMillion);
    }
    public AgentBudget budget(AgentModelProfile profile) {
        if (profile.metered() && !profile.model().equals(pricingModel))
            throw new IllegalStateException("Model pricing must be configured for the selected model");
        return new AgentBudget(profile, maxTotalTokens, 0, 0, maxCostUsd, BigDecimal.ZERO,
                inputTokenAllowance, outputTokenAllowance, inputUsdPerMillion, outputUsdPerMillion);
    }
}
