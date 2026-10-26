package dev.supportflow.chat.infrastructure.deepseek;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Fixed provider diagnostics; request and response data never become metric names or tags. */
final class DeepSeekCallMetrics {
    private final Counter[][] calls = new Counter[DeepSeekCallOperation.values().length][DeepSeekCallOutcome.values().length];
    private final Timer[][] durations = new Timer[DeepSeekCallOperation.values().length][DeepSeekCallOutcome.values().length];

    DeepSeekCallMetrics(MeterRegistry registry, Semaphore slots, int limit) {
        Objects.requireNonNull(registry, "Meter registry is required");
        Objects.requireNonNull(slots, "Call permits are required");
        for (DeepSeekCallOperation operation : DeepSeekCallOperation.values()) {
            for (DeepSeekCallOutcome outcome : DeepSeekCallOutcome.values()) {
                Tags tags = Tags.of("operation", operation.name(), "outcome", outcome.name());
                calls[operation.ordinal()][outcome.ordinal()] = Counter.builder("supportflow.ai.calls")
                        .description("Completed or rejected model calls")
                        .tags(tags).register(registry);
                durations[operation.ordinal()][outcome.ordinal()] = Timer.builder("supportflow.ai.call.duration")
                        .description("Elapsed time of a model call including admission and parsing")
                        .tags(tags).register(registry);
            }
        }
        Gauge.builder("supportflow.ai.inflight", slots, permits -> limit - permits.availablePermits())
                .description("Model calls currently holding a shared execution permit")
                .register(registry);
        Gauge.builder("supportflow.ai.limit", slots, ignored -> limit)
                .description("Maximum concurrent model calls for this application instance")
                .register(registry);
    }

    void record(DeepSeekCallOperation operation, DeepSeekCallOutcome outcome, long elapsedNanos) {
        calls[operation.ordinal()][outcome.ordinal()].increment();
        durations[operation.ordinal()][outcome.ordinal()].record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }
}
