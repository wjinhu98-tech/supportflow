package dev.supportflow.chat.infrastructure.deepseek;

import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.traffic.TrafficLimitExceededException;
import dev.supportflow.common.traffic.TrafficProperties;
import dev.supportflow.common.traffic.TrafficQuotaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepSeekModelQuotaTest {
    private final HttpClient client = mock(HttpClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    @AfterEach void clear() { SecurityContextHolder.clearContext(); registry.close(); }

    @Test void allFourOperationsShareAnOwnersSequentialQuotaAndNeverSendDeniedCalls() {
        var executor = executor(4);
        respond(200);
        authenticate(200);
        for (var operation : DeepSeekCallOperation.values())
            assertThat(executor.<String>execute(operation, "{}", bytes -> "safe-result")).isEqualTo("safe-result");
        assertThatThrownBy(() -> executor.execute(DeepSeekCallOperation.ORDER_INTENT, "{}", bytes -> "denied"))
                .isInstanceOf(TrafficLimitExceededException.class);
        verify(client, times(4)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        authenticate(201);
        assertThat(executor.<String>execute(DeepSeekCallOperation.AGENT_DECISION, "{}", bytes -> "other-owner")).isEqualTo("other-owner");
        verify(client, times(5)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test void providerFailuresAndRetriesStillConsumeTheOriginalOwnersBudget() {
        var executor = executor(1);
        authenticate(200);
        respond(503);
        assertThatThrownBy(() -> executor.execute(DeepSeekCallOperation.AGENT_DECISION, "{}", bytes -> "never"))
                .isInstanceOf(ModelUnavailableException.class);
        assertThatThrownBy(() -> executor.execute(DeepSeekCallOperation.AGENT_DECISION, "{}", bytes -> "retry"))
                .isInstanceOf(TrafficLimitExceededException.class);
        verify(client, times(1)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test void adapterDoesNotConvertQuotaRejectionIntoGenericModelFailure() {
        var executor = executor(1);
        authenticate(200);
        respond(200);
        executor.execute(DeepSeekCallOperation.ORDER_INTENT, "{}", bytes -> "first");
        var recognizer = new DeepSeekOrderIntentRecognizer(executor, tools.jackson.databind.json.JsonMapper.builder().build(), properties());
        assertThatThrownBy(() -> recognizer.recognize("这件商品怎么保养"))
                .isInstanceOf(TrafficLimitExceededException.class);
        verify(client, times(1)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test void publicKnowledgeSelectorFallsBackLocallyWithoutBypassingAnExhaustedQuota() {
        var executor = executor(1);
        authenticate(200);
        respond(200);
        executor.execute(DeepSeekCallOperation.ORDER_INTENT, "{}", bytes -> "first");
        var selector = new DeepSeekPublicKnowledgeSelector(executor, tools.jackson.databind.json.JsonMapper.builder().build(), properties());
        var source = new dev.supportflow.assistant.knowledge.PublicReferenceCatalog()
                .find(102, "ref-android-bluetooth-reconnect").orElseThrow();
        assertThat(selector.select(102, "蓝牙耳机怎么重新配对？", List.of(source))).isEmpty();
        verify(client, times(1)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    private DeepSeekCallExecutor executor(int limit) {
        var quota = new TrafficQuotaService(new TrafficProperties(true, 20, Duration.ofMinutes(1),
                limit, Duration.ofHours(1), 16), registry, () -> 0);
        return new DeepSeekCallExecutor(client, properties(), registry, quota);
    }

    private DeepSeekProperties properties() {
        return new DeepSeekProperties(true, "quota-test-key", "deepseek-flash", Duration.ofSeconds(1), Duration.ofSeconds(1), 2);
    }

    @SuppressWarnings("unchecked") private void respond(int status) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new byte[]{1});
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private void authenticate(long buyerId) {
        var principal = new CustomerPrincipal("buyer-" + buyerId, "unused", new CustomerIdentity(buyerId), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
}
