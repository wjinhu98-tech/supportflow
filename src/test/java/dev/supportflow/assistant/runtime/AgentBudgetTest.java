package dev.supportflow.assistant.runtime;

import dev.supportflow.assistant.runtime.model.AgentBudget;
import dev.supportflow.assistant.runtime.model.AgentModelProfile;
import dev.supportflow.assistant.runtime.model.AgentTokenUsage;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentBudgetTest {
    private final AgentRuntimeProperties properties = new AgentRuntimeProperties(8, 90);
    private final AgentModelProfile profile = new AgentModelProfile("deepseek-flash", true);

    @Test void reservesBeforeSendingAndRefundsOnlyKnownValidatedUsage() {
        AgentBudget empty = properties.budget(profile);
        AgentBudget reserved = empty.reserve();
        assertThat(empty.chargedTokens()).isZero();
        assertThat(reserved.chargedTokens()).isEqualTo(16896);
        assertThat(reserved.reportedTokens()).isZero();
        assertThat(reserved.chargedCostUsd()).isEqualByComparingTo("0.0055296");
        AgentBudget settled = reserved.settle(new AgentTokenUsage(200, 20));
        assertThat(settled.chargedTokens()).isEqualTo(220);
        assertThat(settled.reportedTokens()).isEqualTo(220);
        assertThat(settled.chargedCostUsd()).isEqualByComparingTo("0.000084");
    }

    @Test void rejectsMissingOrExcessUsageInsteadOfCreditingUnknownCalls() {
        AgentBudget reserved = properties.budget(profile).reserve();
        for (AgentTokenUsage usage : new AgentTokenUsage[]{AgentTokenUsage.NONE,
                new AgentTokenUsage(16385, 1), new AgentTokenUsage(1, 513)}) {
            assertThatThrownBy(() -> reserved.settle(usage)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(reserved.chargedTokens()).isEqualTo(16896);
        assertThatThrownBy(() -> properties.budget(profile).settle(new AgentTokenUsage(200, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void costCeilingAndTokenCeilingAreIndependent() {
        AgentBudget tokens = new AgentBudget(profile, 100, 0, 0, new BigDecimal("1"), BigDecimal.ZERO,
                16384, 512, new BigDecimal("0.3"), new BigDecimal("1.2"));
        AgentBudget money = new AgentBudget(profile, 1000000, 0, 0, new BigDecimal("0.000001"), BigDecimal.ZERO,
                16384, 512, new BigDecimal("0.3"), new BigDecimal("1.2"));
        assertThat(tokens.canReserve()).isFalse();
        assertThat(money.canReserve()).isFalse();
        assertThatThrownBy(tokens::reserve).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(money::reserve).isInstanceOf(IllegalStateException.class);
    }

    @Test void rulePlannerNeverClaimsPaidTokensOrCost() {
        AgentBudget rules = properties.budget(new AgentModelProfile("rules", false)).reserve().settle(AgentTokenUsage.NONE);
        assertThat(rules.chargedTokens()).isZero();
        assertThat(rules.reportedTokens()).isZero();
        assertThat(rules.chargedCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThatThrownBy(() -> rules.settle(new AgentTokenUsage(1, 1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void changingModelRequiresMatchingExplicitPricingRatherThanUsingAnUnrelatedRate() {
        assertThatThrownBy(() -> properties.budget(new AgentModelProfile("other-model", true)))
                .isInstanceOf(IllegalStateException.class);
    }
}
