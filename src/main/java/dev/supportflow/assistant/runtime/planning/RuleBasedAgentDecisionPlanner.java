package dev.supportflow.assistant.runtime.planning;

import dev.supportflow.assistant.planning.ExplicitOrderNumbers;
import dev.supportflow.assistant.runtime.model.AgentAction;
import dev.supportflow.assistant.runtime.model.AgentDecision;
import dev.supportflow.assistant.runtime.model.AgentModelProfile;
import dev.supportflow.assistant.runtime.model.AgentTokenUsage;
import dev.supportflow.order.domain.model.OrderStatus;
import java.util.UUID;

/** Offline deterministic counterpart, exercising the same loop with zero provider usage. */
public final class RuleBasedAgentDecisionPlanner implements AgentDecisionPlanner {
    @Override public AgentModelProfile profile() { return new AgentModelProfile("rules", false); }
    @Override public AgentDecisionResult decide(AgentDecisionInput input) {
        var order = input.state().order();
        String number = order == null ? input.selectedOrderNo() : order.orderNo();
        var explicit = ExplicitOrderNumbers.extract(input.message());
        if (order == null && explicit.size() == 1) number = explicit.iterator().next();
        AgentAction action;
        if (order == null) action = number == null || explicit.size() > 1 ? AgentAction.CLARIFY : AgentAction.ORDER_LOOKUP;
        else if (input.state().logistics() == null && (order.orderStatus() == OrderStatus.SHIPPED || order.orderStatus() == OrderStatus.COMPLETED))
            action = AgentAction.LOGISTICS_LOOKUP;
        else action = AgentAction.ANSWER;
        return new AgentDecisionResult(new AgentDecision(action, action == AgentAction.CLARIFY ? null : number,
                "rule_" + UUID.randomUUID()), AgentTokenUsage.NONE);
    }
}
