package dev.supportflow.chat.infrastructure.deepseek;

import dev.supportflow.chat.application.ModelFailureKind;
import dev.supportflow.chat.application.ModelUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentMatchers;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeepSeekCallResilienceTest {
    private final HttpClient client = mock(HttpClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test void oneSharedCircuitRejectsAllOperationTypesWithoutSendingAnotherHttpRequest() {
        var executor = executor(2);
        respond(503);
        assertKind(() -> executor.execute(DeepSeekCallOperation.ORDER_INTENT, "{}", body -> "ok"), ModelFailureKind.PROVIDER_TEMPORARY);
        assertKind(() -> executor.execute(DeepSeekCallOperation.AGENT_DECISION, "{}", body -> "ok"), ModelFailureKind.PROVIDER_TEMPORARY);
        assertKind(() -> executor.execute(DeepSeekCallOperation.TOOL_PLAN, "{}", body -> "ok"), ModelFailureKind.CIRCUIT_OPEN);
        verify(client, times(2)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertThat(registry.get("supportflow.ai.inflight").gauge().value()).isZero();
        assertThat(registry.get("supportflow.ai.calls").tags("operation", "TOOL_PLAN", "outcome", "CIRCUIT_OPEN")
                .counter().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"400,PROVIDER_REJECTED", "401,PROVIDER_REJECTED", "402,PROVIDER_REJECTED",
            "422,PROVIDER_REJECTED", "429,RATE_LIMITED", "500,PROVIDER_TEMPORARY", "503,PROVIDER_TEMPORARY"})
    void httpStatusIsClassifiedWithoutAutomaticTransportRetries(int status, ModelFailureKind expected) {
        respond(status);
        assertKind(() -> executor(5).execute(DeepSeekCallOperation.AGENT_DECISION, "{}", body -> "unused"), expected);
        verify(client, times(1)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test void remainingRunTimeBoundsTheWholeBodyAndCancelsThePendingRequest() {
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            assertThat(request.timeout()).contains(Duration.ofMillis(25));
            return pending;
        });
        assertKind(() -> executor(5).execute(DeepSeekCallOperation.AGENT_DECISION, "{}", body -> "unused", Duration.ofMillis(25)),
                ModelFailureKind.TIMEOUT);
        assertThat(pending.isCancelled()).isTrue();
        assertThat(registry.get("supportflow.ai.inflight").gauge().value()).isZero();
    }

    private DeepSeekCallExecutor executor(int threshold) {
        return new DeepSeekCallExecutor(client, new DeepSeekProperties(true, "fake-circuit-test-key", "deepseek-flash",
                Duration.ofSeconds(1), Duration.ofSeconds(2), 2,
                new DeepSeekCircuitProperties(threshold, Duration.ofSeconds(30))), registry);
    }
    @SuppressWarnings("unchecked") private void respond(int status) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn("private-provider-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }
    private static void assertKind(Runnable work, ModelFailureKind kind) {
        assertThatThrownBy(work::run).isExactlyInstanceOf(ModelUnavailableException.class).hasNoCause()
                .hasMessageNotContaining("private-provider-marker")
                .satisfies(failure -> assertThat(((ModelUnavailableException) failure).kind()).isEqualTo(kind));
    }
}
