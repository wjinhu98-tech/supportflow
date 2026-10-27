package dev.supportflow.assistant.runtime.model;

import dev.supportflow.order.domain.model.OrderStatus;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Public business facts only, never internal IDs, credentials or model reasoning. */
public record AgentObservation(AgentAction action, String orderNo, String callId, String content, OrderStatus orderStatus) {
    public AgentObservation {
        new AgentDecision(action, orderNo, callId);
        if (action != AgentAction.ORDER_LOOKUP && action != AgentAction.LOGISTICS_LOOKUP)
            throw new IllegalArgumentException("Only executed tools produce observations");
        Objects.requireNonNull(orderNo);
        if (content == null || content.isBlank() || content.length() > 18000
                || content.getBytes(StandardCharsets.UTF_8).length > 60000)
            throw new IllegalArgumentException("Observation exceeds bounds");
        if (action == AgentAction.ORDER_LOOKUP && orderStatus == null)
            throw new IllegalArgumentException("An order observation requires a status");
    }
}
