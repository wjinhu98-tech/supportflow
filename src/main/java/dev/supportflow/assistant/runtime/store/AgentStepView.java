package dev.supportflow.assistant.runtime.store;

import java.time.Instant;
import java.util.UUID;
import java.math.BigDecimal;

/** Bounded progress metadata; deliberately excludes tool arguments and result snapshots. */
public record AgentStepView(UUID operationId, int attempt, AgentPhase phase, AgentStepStatus status,
                            Instant startedAt, Instant completedAt, AgentMeteringStatus meteringStatus,
                            long reservedTokens, Long reportedTokens, BigDecimal estimatedCostUsd,
                            AgentStepFailure failureKind, boolean retryScheduled) {
    public AgentStepView(UUID operationId, int attempt, AgentPhase phase, AgentStepStatus status,
                         Instant startedAt, Instant completedAt, AgentMeteringStatus meteringStatus,
                         long reservedTokens, Long reportedTokens, BigDecimal estimatedCostUsd) {
        this(operationId, attempt, phase, status, startedAt, completedAt, meteringStatus, reservedTokens,
                reportedTokens, estimatedCostUsd, null, false);
    }
    public AgentStepView(UUID operationId, int attempt, AgentPhase phase, AgentStepStatus status,
                         Instant startedAt, Instant completedAt) {
        this(operationId, attempt, phase, status, startedAt, completedAt, AgentMeteringStatus.NONE, 0, null, BigDecimal.ZERO);
    }
}
