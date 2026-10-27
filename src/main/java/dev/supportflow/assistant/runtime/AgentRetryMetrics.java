package dev.supportflow.assistant.runtime;

import dev.supportflow.assistant.runtime.store.AgentPhase;
import dev.supportflow.assistant.runtime.store.AgentStepFailure;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/** Fixed phase/outcome dimensions; no request IDs, usernames, order numbers or exception text. */
final class AgentRetryMetrics {
    private final MeterRegistry registry;
    AgentRetryMetrics(MeterRegistry registry) { this.registry = registry; }

    void step(AgentPhase phase, long nanos, boolean success) {
        Timer.builder("supportflow.agent.step.duration").tags("phase", phase.name(), "outcome", success ? "SUCCESS" : "FAILURE")
                .register(registry).record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }
    void failure(AgentPhase phase, AgentStepFailure failure, boolean scheduled) {
        Counter.builder("supportflow.agent.retry.decisions")
                .tags("phase", phase.name(), "failure", failure.name(), "outcome", scheduled ? "SCHEDULED" : "STOPPED")
                .register(registry).increment();
    }
}
