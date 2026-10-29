package dev.supportflow.assistant.evaluation;

import dev.supportflow.assistant.runtime.model.AgentAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Fake transport only. An environment key in these tests is deliberately not a real credential. */
class RealModelEvaluationToolTest {
    private static final String KEY = "evaluation-private-test-marker";
    private final HttpClient client = mock(HttpClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong nanoTime = new AtomicLong();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
    @TempDir Path directory;
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void disabledByDefaultDoesNotCreateAClientOrReportOrReadCredentials() throws Exception {
        int exit = RealModelEvaluationTool.run(new String[0], Map.of(), () -> {
            throw new AssertionError("Disabled evaluation must not create a provider client");
        }, directory, nanoTime::get, clock, new PrintStream(output));
        assertThat(exit).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("NOT_RUN");
        try (var files = Files.list(directory)) { assertThat(files.count()).isZero(); }
        verifyNoInteractions(client);
    }

    @Test void evaluatesEightSyntheticContractsWithStrictAdapterAndRestoresTheOriginalIdentity() throws Exception {
        var original = SecurityContextHolder.createEmptyContext();
        original.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("prior-account", null, List.of()));
        SecurityContextHolder.setContext(original);
        var calls = new AtomicInteger();
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            assertThat(request.uri().toString()).isEqualTo("https://api.deepseek.com/chat/completions");
            assertThat(request.timeout()).hasValueSatisfying(timeout -> assertThat(timeout).isLessThanOrEqualTo(Duration.ofSeconds(8)));
            var fixture = RealModelEvaluationTool.fixtures().get(calls.getAndIncrement());
            return response(200, envelope(fixture.expectedAction(), fixture.expectedOrderNo()));
        });
        assertThat(run("--allow-remote")).isZero();
        assertThat(SecurityContextHolder.getContext()).isSameAs(original);
        JsonNode report = report();
        assertThat(report.path("passed").asInt()).isEqualTo(8);
        assertThat(report.path("failed").asInt()).isZero();
        assertThat(report.path("attemptedCalls").asInt()).isEqualTo(8);
        assertThat(report.path("reportedTokens").asLong()).isEqualTo(880);
        assertThat(report.path("chargedTokens").asLong()).isEqualTo(880);
        assertThat(report.path("datasetSha256").asString()).matches("[0-9a-f]{64}");
        assertSafeReport();
    }

    @Test void malformedProviderDataIsNotLeakedAndFailedAttemptRetainsReservationWithoutRetry() throws Exception {
        var malformed = response(200, ("private-provider-output " + KEY).getBytes(StandardCharsets.UTF_8));
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(malformed);
        assertThat(run("--allow-remote", "--max-calls=1")).isEqualTo(1);
        JsonNode report = report();
        assertThat(report.path("attemptedCalls").asInt()).isEqualTo(1);
        assertThat(report.path("failed").asInt()).isEqualTo(1);
        assertThat(report.path("skipped").asInt()).isEqualTo(7);
        assertThat(report.path("chargedTokens").asLong()).isEqualTo(8192 + 256);
        assertThat(report.path("reportedTokens").asLong()).isZero();
        assertThat(report.path("results").get(0).path("failureKind").asString()).isEqualTo("INVALID_RESPONSE");
        verify(client, times(1)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertSafeReport();
    }

    @Test void conservativeEstimatedCostCeilingStopsBeforeAnyProviderRequest() throws Exception {
        assertThat(run("--allow-remote", "--max-cost-usd=0.000001")).isEqualTo(3);
        assertThat(report().path("attemptedCalls").asInt()).isZero();
        assertThat(report().path("completionReason").asString()).isEqualTo("BUDGET");
        assertThat(report().path("chargedTokens").asLong()).isZero();
        verify(client, times(0)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test void globalDeadlinePreventsASecondCallEvenWhenBudgetRemains() throws Exception {
        var fixture = RealModelEvaluationTool.fixtures().getFirst();
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            nanoTime.set(Duration.ofSeconds(61).toNanos());
            return response(200, envelope(fixture.expectedAction(), fixture.expectedOrderNo()));
        });
        assertThat(run("--allow-remote")).isEqualTo(3);
        assertThat(report().path("attemptedCalls").asInt()).isEqualTo(1);
        assertThat(report().path("completionReason").asString()).isEqualTo("DEADLINE");
        verify(client, times(1)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test void hardCapsExplicitConsentAndPriceEstimatesCannotBeBypassed() {
        for (String[] args : List.of(new String[]{"--max-calls=1"}, new String[]{"--allow-remote", "--max-calls=13"},
                new String[]{"--allow-remote", "--max-cost-usd=1.01"}, new String[]{"--allow-remote", "--deadline-seconds=61"},
                new String[]{"--allow-remote", "--max-tokens=100001"}, new String[]{"--allow-remote", "--api-key=" + KEY},
                new String[]{"--allow-remote", "--allow-remote"}))
            assertThatThrownBy(() -> run(args)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(KEY);
        var invalidRates = new LinkedHashMap<>(environment());
        invalidRates.put("SUPPORTFLOW_EVAL_INPUT_USD_PER_MILLION", "NaN");
        assertThatThrownBy(() -> RealModelEvaluationTool.run(new String[]{"--allow-remote"}, invalidRates, () -> client,
                directory, nanoTime::get, clock, new PrintStream(output))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    private int run(String... args) throws Exception {
        return RealModelEvaluationTool.run(args, environment(), () -> client, directory, nanoTime::get, clock, new PrintStream(output));
    }
    private Map<String, String> environment() {
        return Map.of("DEEPSEEK_API_KEY", KEY, "DEEPSEEK_MODEL", "deepseek-flash",
                "SUPPORTFLOW_EVAL_INPUT_USD_PER_MILLION", "0.30", "SUPPORTFLOW_EVAL_OUTPUT_USD_PER_MILLION", "1.20");
    }
    private JsonNode report() throws Exception {
        try (var files = Files.list(directory)) { return json.readTree(Files.readString(files.findFirst().orElseThrow())); }
    }
    private void assertSafeReport() throws Exception {
        String text = report().toString();
        assertThat(text).doesNotContain(KEY, "private-provider-output", "prior-account", "SF90000001", "selectedOrderNo",
                "\"question\"", "\"prompt\"", "\"content\"", "Authorization", "apiKey");
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(KEY, "private-provider-output");
    }
    private byte[] envelope(AgentAction action, String orderNo) {
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("orderNo", orderNo);
        return json.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", Map.of(
                "role", "assistant", "tool_calls", List.of(Map.of("id", "evaluation-call", "type", "function", "function",
                        Map.of("name", action.code(), "arguments", json.writeValueAsString(arguments))))))),
                "usage", Map.of("prompt_tokens", 100, "completion_tokens", 10, "total_tokens", 110)));
    }
    @SuppressWarnings("unchecked") private CompletableFuture<HttpResponse<byte[]>> response(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return CompletableFuture.completedFuture(response);
    }
}
