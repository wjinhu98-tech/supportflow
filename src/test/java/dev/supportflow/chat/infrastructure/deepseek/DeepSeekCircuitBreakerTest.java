package dev.supportflow.chat.infrastructure.deepseek;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekCircuitBreakerTest {
    private final AtomicLong nanos = new AtomicLong();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DeepSeekCircuitBreaker circuit = new DeepSeekCircuitBreaker(
            new DeepSeekCircuitProperties(2, Duration.ofSeconds(30)), registry, nanos::get);

    @Test void consecutiveFailuresOpenTheCircuitAndOnlyOneProbeCanEnterAfterTheDelay() {
        circuit.complete(circuit.tryAcquire(), DeepSeekCallOutcome.TIMEOUT);
        assertThat(circuit.state()).isEqualTo(DeepSeekCircuitBreaker.State.CLOSED);
        circuit.complete(circuit.tryAcquire(), DeepSeekCallOutcome.PROVIDER_ERROR);
        assertThat(circuit.tryAcquire()).isNull();
        assertThat(registry.get("supportflow.ai.circuit.state").gauge().value()).isEqualTo(1);
        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(circuit.tryAcquire()).isNull();
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        var probe = circuit.tryAcquire();
        assertThat(probe.probe()).isTrue();
        for (int index = 0; index < 50; index++) assertThat(circuit.tryAcquire()).isNull();
        assertThat(registry.get("supportflow.ai.circuit.state").gauge().value()).isEqualTo(2);
        circuit.complete(probe, DeepSeekCallOutcome.SUCCESS);
        assertThat(circuit.state()).isEqualTo(DeepSeekCircuitBreaker.State.CLOSED);
        assertThat(circuit.tryAcquire()).isNotNull();
        assertThat(registry.get("supportflow.ai.circuit.transitions").tags("from", "HALF_OPEN", "to", "CLOSED")
                .counter().count()).isEqualTo(1);
    }

    @Test void lateCompletionsCannotCloseAnOpenCircuitOrPoisonARecoveredGeneration() {
        var late = circuit.tryAcquire();
        circuit.complete(circuit.tryAcquire(), DeepSeekCallOutcome.TIMEOUT);
        circuit.complete(circuit.tryAcquire(), DeepSeekCallOutcome.TIMEOUT);
        circuit.complete(late, DeepSeekCallOutcome.SUCCESS);
        assertThat(circuit.state()).isEqualTo(DeepSeekCircuitBreaker.State.OPEN);
        nanos.addAndGet(Duration.ofSeconds(30).toNanos());
        var probe = circuit.tryAcquire();
        circuit.complete(late, DeepSeekCallOutcome.INVALID_RESPONSE);
        circuit.complete(probe, DeepSeekCallOutcome.SUCCESS);
        circuit.complete(late, DeepSeekCallOutcome.PROVIDER_ERROR);
        circuit.complete(circuit.tryAcquire(), DeepSeekCallOutcome.TIMEOUT);
        assertThat(circuit.state()).isEqualTo(DeepSeekCircuitBreaker.State.CLOSED);
    }

    @Test void successfulCallsResetConsecutiveFailuresAndInterruptedProbesReleaseTheirSlot() {
        circuit.complete(circuit.tryAcquire(), DeepSeekCallOutcome.TIMEOUT);
        circuit.complete(circuit.tryAcquire(), DeepSeekCallOutcome.SUCCESS);
        circuit.complete(circuit.tryAcquire(), DeepSeekCallOutcome.INTERRUPTED);
        circuit.complete(circuit.tryAcquire(), DeepSeekCallOutcome.TIMEOUT);
        assertThat(circuit.state()).isEqualTo(DeepSeekCircuitBreaker.State.CLOSED);
        circuit.complete(circuit.tryAcquire(), DeepSeekCallOutcome.TIMEOUT);
        nanos.addAndGet(Duration.ofSeconds(30).toNanos());
        circuit.complete(circuit.tryAcquire(), DeepSeekCallOutcome.INTERRUPTED);
        assertThat(circuit.state()).isEqualTo(DeepSeekCircuitBreaker.State.OPEN);
        nanos.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(circuit.tryAcquire()).isNotNull();
    }

    @Test void circuitLimitsCannotDisableProtectionWithInvalidValues() {
        assertThatThrownBy(() -> new DeepSeekCircuitProperties(0, Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeepSeekCircuitProperties(5, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeepSeekCircuitProperties(5, Duration.ofMinutes(6))).isInstanceOf(IllegalArgumentException.class);
    }
}
