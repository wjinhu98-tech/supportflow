package dev.supportflow.chat.infrastructure.deepseek;

import dev.supportflow.chat.application.ModelUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Isolated metrics and admission tests: no network, model, private configuration or business database. */
class DeepSeekCallMetricsTest {
    private static final String PRIVATE_KEY = "metrics-private-api-key-marker";
    private static final String PRIVATE_MESSAGE = "metrics-private-message-marker";
    private static final String PRIVATE_ORDER = "SFMETRICSPRIVATE001";
    private static final String PRIVATE_RESPONSE = "metrics-private-response-marker";
    private static final String REQUEST_BODY = "{\"message\":\"" + PRIVATE_MESSAGE
            + "\",\"orderNo\":\"" + PRIVATE_ORDER + "\"}";
    private static final byte[] OK = "ok".getBytes(StandardCharsets.UTF_8);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final HttpClient client = mock(HttpClient.class);

    @AfterEach
    void metricsUseOnlyFixedSafeDimensions() {
        try {
            for (Meter meter : registry.getMeters()) {
                Meter.Id id = meter.getId();
                assertThat(id.getName()).isIn("supportflow.ai.calls", "supportflow.ai.call.duration",
                        "supportflow.ai.inflight", "supportflow.ai.limit", "supportflow.ai.circuit.state",
                        "supportflow.ai.circuit.transitions");
                if (id.getName().equals("supportflow.ai.circuit.transitions")) {
                    assertThat(id.getType()).isEqualTo(Meter.Type.COUNTER);
                    assertThat(id.getTags()).extracting(tag -> tag.getKey()).containsExactlyInAnyOrder("from", "to");
                    assertThat(id.getTag("from")).isIn("CLOSED", "OPEN", "HALF_OPEN");
                    assertThat(id.getTag("to")).isIn("CLOSED", "OPEN", "HALF_OPEN");
                } else if (id.getType() == Meter.Type.COUNTER || id.getType() == Meter.Type.TIMER) {
                    assertThat(id.getTags()).extracting(tag -> tag.getKey())
                            .containsExactlyInAnyOrder("operation", "outcome");
                    assertThat(id.getTag("operation")).isIn("ORDER_INTENT", "TOOL_PLAN", "AGENT_DECISION");
                    assertThat(id.getTag("outcome")).isIn("SUCCESS", "BUSY", "TIMEOUT", "INTERRUPTED",
                            "PROVIDER_RATE_LIMITED", "PROVIDER_ERROR", "INVALID_RESPONSE", "TRANSPORT_ERROR", "CANCELLED", "CIRCUIT_OPEN");
                } else {
                    assertThat(id.getType()).isEqualTo(Meter.Type.GAUGE);
                    assertThat(id.getTags()).isEmpty();
                }
                for (String secret : List.of(PRIVATE_KEY, PRIVATE_MESSAGE, PRIVATE_ORDER, PRIVATE_RESPONSE,
                        REQUEST_BODY, "https://api.deepseek.com")) {
                    assertThat(id.toString().contains(secret)).as("Metric identity must not contain request data").isFalse();
                }
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void everyFixedOperationAndOutcomeHasOneCounterAndOneDurationMeasuredInConvertibleTimeUnits() {
        var metrics = new DeepSeekCallMetrics(registry, new Semaphore(2), 2);
        for (DeepSeekCallOperation operation : DeepSeekCallOperation.values()) {
            for (DeepSeekCallOutcome outcome : DeepSeekCallOutcome.values()) {
                metrics.record(operation, outcome, TimeUnit.MILLISECONDS.toNanos(25));
                assertThat(calls(operation, outcome).count()).isEqualTo(1);
                assertThat(duration(operation, outcome).count()).isEqualTo(1);
                assertThat(duration(operation, outcome).totalTime(TimeUnit.SECONDS)).isCloseTo(0.025, within(0.000000001));
            }
        }
        assertThat(totalCalls()).isEqualTo(30);
        assertThat(totalDurations()).isEqualTo(30);
    }

    @ParameterizedTest
    @EnumSource(value = DeepSeekCallOutcome.class, names = {"TIMEOUT", "INTERRUPTED", "PROVIDER_RATE_LIMITED",
            "PROVIDER_ERROR", "INVALID_RESPONSE", "TRANSPORT_ERROR", "CANCELLED"})
    void eachClassifiedFailureRecordsOnceAndReleasesItsRealPermitForRecovery(DeepSeekCallOutcome expected) {
        var executor = new DeepSeekCallExecutor(client, properties(1), registry);
        DeepSeekCallOperation operation = expected.ordinal() % 2 == 0
                ? DeepSeekCallOperation.ORDER_INTENT : DeepSeekCallOperation.TOOL_PLAN;
        CompletableFuture<HttpResponse<byte[]>> pending = switch (expected) {
            case TIMEOUT -> CompletableFuture.failedFuture(new HttpTimeoutException(privateText()));
            case INTERRUPTED -> new CompletableFuture<>();
            case PROVIDER_RATE_LIMITED -> CompletableFuture.completedFuture(response(429, privateText().getBytes(StandardCharsets.UTF_8)));
            case PROVIDER_ERROR -> CompletableFuture.completedFuture(response(503, privateText().getBytes(StandardCharsets.UTF_8)));
            case INVALID_RESPONSE -> CompletableFuture.completedFuture(response(200, OK));
            case TRANSPORT_ERROR -> CompletableFuture.failedFuture(new IOException(privateText()));
            case CANCELLED -> {
                var cancelled = new CompletableFuture<HttpResponse<byte[]>>();
                cancelled.cancel(false);
                yield cancelled;
            }
            default -> throw new AssertionError("Unexpected failure test case");
        };
        doReturn(pending).when(client).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        try {
            if (expected == DeepSeekCallOutcome.INTERRUPTED) Thread.currentThread().interrupt();
            assertThatThrownBy(() -> executor.execute(operation, REQUEST_BODY, body -> {
                if (expected == DeepSeekCallOutcome.INVALID_RESPONSE) throw new IllegalArgumentException(privateText());
                return "unexpected";
            })).isExactlyInstanceOf(ModelUnavailableException.class).hasNoCause()
                    .hasMessageNotContaining(PRIVATE_KEY).hasMessageNotContaining(PRIVATE_MESSAGE)
                    .hasMessageNotContaining(PRIVATE_ORDER).hasMessageNotContaining(PRIVATE_RESPONSE);
            if (expected == DeepSeekCallOutcome.INTERRUPTED) {
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(pending.isCancelled()).isTrue();
            }
        } finally {
            if (expected == DeepSeekCallOutcome.INTERRUPTED) Thread.interrupted();
        }

        assertThat(inflight()).isZero();
        assertThat(limit()).isEqualTo(1);
        assertThat(calls(operation, expected).count()).isEqualTo(1);
        assertThat(duration(operation, expected).count()).isEqualTo(1);
        assertThat(duration(operation, expected).totalTime(TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0);

        doReturn(CompletableFuture.completedFuture(response(200, OK))).when(client)
                .sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        String recovered = executor.execute(operation, REQUEST_BODY, body -> "recovered");
        assertThat(recovered).isEqualTo("recovered");
        assertThat(inflight()).isZero();
        assertThat(calls(operation, DeepSeekCallOutcome.SUCCESS).count()).isEqualTo(1);
        assertThat(duration(operation, DeepSeekCallOutcome.SUCCESS).count()).isEqualTo(1);
        assertThat(totalCalls()).isEqualTo(2);
        assertThat(totalDurations()).isEqualTo(2);
        verify(client, times(2)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test
    void concurrentAdmissionAndBusyResultsReflectTheSameSemaphoreWithoutInflatingTheGauge() throws Exception {
        var executor = new DeepSeekCallExecutor(client, properties(2), registry);
        var sent = new CountDownLatch(2);
        List<CompletableFuture<HttpResponse<byte[]>>> pending = new CopyOnWriteArrayList<>();
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> {
                    var response = new CompletableFuture<HttpResponse<byte[]>>();
                    pending.add(response);
                    sent.countDown();
                    return response;
                });

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> executor.execute(
                    DeepSeekCallOperation.ORDER_INTENT, REQUEST_BODY, body -> "first"), workers);
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> executor.execute(
                    DeepSeekCallOperation.TOOL_PLAN, REQUEST_BODY, body -> "second"), workers);
            try {
                assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(inflight()).isEqualTo(2);
                assertThat(limit()).isEqualTo(2);
                assertThat(totalCalls()).isZero();
                assertThatThrownBy(() -> executor.execute(DeepSeekCallOperation.TOOL_PLAN, REQUEST_BODY, body -> "rejected"))
                        .isExactlyInstanceOf(ModelUnavailableException.class).hasNoCause();
                assertThat(calls(DeepSeekCallOperation.TOOL_PLAN, DeepSeekCallOutcome.BUSY).count()).isEqualTo(1);
                assertThat(duration(DeepSeekCallOperation.TOOL_PLAN, DeepSeekCallOutcome.BUSY).count()).isEqualTo(1);
                assertThat(inflight()).isEqualTo(2);
                verify(client, times(2)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());

                for (var response : pending) response.complete(response(200, OK));
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
                assertThat(inflight()).isZero();
                assertThat(calls(DeepSeekCallOperation.ORDER_INTENT, DeepSeekCallOutcome.SUCCESS).count()).isEqualTo(1);
                assertThat(calls(DeepSeekCallOperation.TOOL_PLAN, DeepSeekCallOutcome.SUCCESS).count()).isEqualTo(1);
                assertThat(totalCalls()).isEqualTo(3);
                assertThat(totalDurations()).isEqualTo(3);

                doReturn(CompletableFuture.completedFuture(response(200, OK))).when(client)
                        .sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
                String recovered = executor.execute(DeepSeekCallOperation.ORDER_INTENT, REQUEST_BODY, body -> "recovered");
                assertThat(recovered).isEqualTo("recovered");
                assertThat(inflight()).isZero();
                assertThat(totalCalls()).isEqualTo(4);
                assertThat(totalDurations()).isEqualTo(4);
            } finally {
                for (var response : pending) response.complete(response(200, OK));
            }
        }
    }

    @Test
    void springConfigurationUsesTheExistingApplicationRegistryWithoutAnExternalCall() {
        new ApplicationContextRunner().withUserConfiguration(DeepSeekConfiguration.class)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withBean(io.micrometer.core.instrument.MeterRegistry.class, () -> registry)
                .withPropertyValues("supportflow.ai.deepseek.enabled=true",
                        "supportflow.ai.deepseek.api-key=" + PRIVATE_KEY,
                        "supportflow.ai.deepseek.max-concurrent-requests=3")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DeepSeekCallExecutor.class)
                            .hasSingleBean(io.micrometer.core.instrument.MeterRegistry.class);
                    assertThat(context.getBean(io.micrometer.core.instrument.MeterRegistry.class)).isSameAs(registry);
                    assertThat(inflight()).isZero();
                    assertThat(limit()).isEqualTo(3);
                    assertThat(totalCalls()).isZero();
                });
    }

    private Counter calls(DeepSeekCallOperation operation, DeepSeekCallOutcome outcome) {
        return registry.get("supportflow.ai.calls").tags("operation", operation.name(), "outcome", outcome.name()).counter();
    }

    private Timer duration(DeepSeekCallOperation operation, DeepSeekCallOutcome outcome) {
        return registry.get("supportflow.ai.call.duration").tags("operation", operation.name(), "outcome", outcome.name()).timer();
    }

    private double totalCalls() {
        return registry.find("supportflow.ai.calls").counters().stream().mapToDouble(Counter::count).sum();
    }

    private long totalDurations() {
        return registry.find("supportflow.ai.call.duration").timers().stream().mapToLong(Timer::count).sum();
    }

    private double inflight() { return registry.get("supportflow.ai.inflight").gauge().value(); }
    private double limit() { return registry.get("supportflow.ai.limit").gauge().value(); }

    private static DeepSeekProperties properties(int limit) {
        return new DeepSeekProperties(true, PRIVATE_KEY, "deepseek-flash", Duration.ofSeconds(1), Duration.ofSeconds(30), limit);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static String privateText() { return PRIVATE_KEY + " " + PRIVATE_MESSAGE + " " + PRIVATE_ORDER + " " + PRIVATE_RESPONSE; }
}
