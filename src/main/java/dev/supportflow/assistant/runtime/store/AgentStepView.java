package dev.supportflow.assistant.runtime.store;

import java.time.Instant;
import java.util.UUID;

/** Bounded progress metadata; deliberately excludes tool arguments and result snapshots. */
public record AgentStepView(UUID operationId, int attempt, AgentPhase phase, AgentStepStatus status,
                            Instant startedAt, Instant completedAt) { }
