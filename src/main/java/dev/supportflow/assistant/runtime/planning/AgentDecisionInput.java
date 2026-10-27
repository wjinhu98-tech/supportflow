package dev.supportflow.assistant.runtime.planning;

import dev.supportflow.assistant.runtime.model.AgentLoopState;
import java.util.Objects;

public record AgentDecisionInput(String message, String selectedOrderNo, AgentLoopState state,
                                  int inputAllowance, int outputAllowance, java.time.Duration callTimeout) {
    public AgentDecisionInput(String message, String selectedOrderNo, AgentLoopState state, int inputAllowance, int outputAllowance) {
        this(message, selectedOrderNo, state, inputAllowance, outputAllowance, java.time.Duration.ofSeconds(15));
    }
    public AgentDecisionInput {
        if (message == null || message.isBlank() || message.length() > 1000) throw new IllegalArgumentException("Invalid question");
        Objects.requireNonNull(state);
        if (callTimeout == null || callTimeout.isNegative() || callTimeout.isZero()
                || callTimeout.compareTo(java.time.Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("Invalid call time allowance");
        if (inputAllowance < 2048 || inputAllowance > 32768 || outputAllowance < 64 || outputAllowance > 1024)
            throw new IllegalArgumentException("Invalid model allowance");
    }
}
