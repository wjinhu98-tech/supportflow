package dev.supportflow.assistant.runtime.model;

import java.util.List;

public record AgentLoopState(List<AgentObservation> observations, AgentDecision pending) {
    public AgentLoopState {
        observations = List.copyOf(observations);
        if (observations.size() > 2 || observations.stream().map(AgentObservation::action).distinct().count() != observations.size()
                || observations.stream().map(AgentObservation::orderNo).distinct().count() > 1)
            throw new IllegalArgumentException("Invalid read-only observation history");
        if (!observations.isEmpty() && observations.getFirst().action() != AgentAction.ORDER_LOOKUP)
            throw new IllegalArgumentException("Ownership-checked order facts must precede logistics");
    }
    public static AgentLoopState empty() { return new AgentLoopState(List.of(), null); }
    public AgentObservation order() { return observations.isEmpty() ? null : observations.getFirst(); }
    public AgentObservation logistics() { return observations.size() == 2 ? observations.getLast() : null; }
}
