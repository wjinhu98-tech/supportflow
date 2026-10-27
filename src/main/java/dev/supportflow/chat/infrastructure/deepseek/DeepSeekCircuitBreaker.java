package dev.supportflow.chat.infrastructure.deepseek;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Per application instance. Monotonic time, one half-open probe, and fenced late completions. */
final class DeepSeekCircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }
    record Permit(long generation, boolean probe) { }

    private final DeepSeekCircuitProperties properties;
    private final LongSupplier nanos;
    private final Counter[][] transitions = new Counter[3][3];
    private State state = State.CLOSED;
    private int failures;
    private long generation;
    private long openedAt;

    DeepSeekCircuitBreaker(DeepSeekCircuitProperties properties, MeterRegistry registry, LongSupplier nanos) {
        this.properties = Objects.requireNonNull(properties);
        this.nanos = Objects.requireNonNull(nanos);
        for (State from : State.values()) for (State to : State.values()) {
            transitions[from.ordinal()][to.ordinal()] = Counter.builder("supportflow.ai.circuit.transitions")
                    .description("Local model circuit state transitions")
                    .tags("from", from.name(), "to", to.name()).register(registry);
        }
        Gauge.builder("supportflow.ai.circuit.state", this, circuit -> circuit.state().ordinal())
                .description("Local model circuit: 0 closed, 1 open, 2 half-open").register(registry);
    }

    synchronized Permit tryAcquire() {
        if (state == State.OPEN && nanos.getAsLong() - openedAt >= properties.openDuration().toNanos()) {
            transition(State.HALF_OPEN);
            return new Permit(generation, true);
        }
        return state == State.CLOSED ? new Permit(generation, false) : null;
    }

    synchronized void complete(Permit permit, DeepSeekCallOutcome outcome) {
        if (permit == null || permit.generation() != generation) return;
        if (permit.probe()) {
            if (state != State.HALF_OPEN) return;
            if (outcome == DeepSeekCallOutcome.SUCCESS) {
                failures = 0;
                generation++;
                transition(State.CLOSED);
            } else open(); // Interrupted/cancelled probes must not leave the circuit stuck half-open.
        } else if (state == State.CLOSED) {
            if (outcome == DeepSeekCallOutcome.SUCCESS) failures = 0;
            else if (countsAsFailure(outcome) && ++failures >= properties.failureThreshold()) open();
        }
    }

    synchronized State state() { return state; }

    private static boolean countsAsFailure(DeepSeekCallOutcome outcome) {
        return switch (outcome) {
            case TIMEOUT, TRANSPORT_ERROR, PROVIDER_RATE_LIMITED, PROVIDER_ERROR, INVALID_RESPONSE -> true;
            default -> false;
        };
    }
    private void open() {
        openedAt = nanos.getAsLong();
        generation++;
        transition(State.OPEN);
    }
    private void transition(State next) {
        if (state != next) transitions[state.ordinal()][next.ordinal()].increment();
        state = next;
    }
}
