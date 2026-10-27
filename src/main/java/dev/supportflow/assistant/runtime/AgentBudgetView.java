package dev.supportflow.assistant.runtime;

import dev.supportflow.assistant.runtime.model.AgentBudget;
import java.math.BigDecimal;

/** Cost estimates use the run's frozen rates. Unconfirmed reservations are not claimed as provider-reported usage. */
public record AgentBudgetView(String model, long tokenLimit, long chargedTokens, long reportedTokens,
                               long unconfirmedTokens, BigDecimal estimatedCostUsd, BigDecimal costLimitUsd, String currency) {
    public static AgentBudgetView from(AgentBudget budget) {
        return budget == null ? null : new AgentBudgetView(budget.profile().model(), budget.maxTokens(), budget.chargedTokens(),
                budget.reportedTokens(), budget.chargedTokens() - budget.reportedTokens(),
                budget.chargedCostUsd(), budget.maxCostUsd(), "USD");
    }
}
